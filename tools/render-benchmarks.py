#!/usr/bin/env python3

import argparse
import csv
import hashlib
import json
import math
import re
import sys
from pathlib import Path


RELOCATORS = {"yp-planner", "reactive-ilp", "proactive-ilp"}
STATUSES = {"success", "partial", "timeout", "error", "running", "interrupted"}
COMMON_FIELDS = (
    "snapshot_sha256", "operations_sha256", "scenario_digest", "source_operations_digest",
    "normalization", "announcement", "bulk_size", "max_operations", "gpu_model", "probe_cap",
    "catalogue_limit", "catalogue_weights", "catalogue_units", "catalogue_checksum", "probe_count",
    "probe_checksum", "processed_operations",
)
OPTIONAL_COMMON_FIELDS = ("dataset_id", "java", "raw_operations", "normalized_operations", "unit_count")
CONTROL_FIELDS = ("max_operations", "gpu_model", "probe_cap", "catalogue_limit", "catalogue_weights")
CSV_FIELDS = (
    "cloud", "bulk_size", "mode", "alias", "algorithm", "parameters", "status", "baseline",
    "baseline_bulk_size", "latency_measurement", "avg_ms", "delta_avg_pct", "maintenance_ms",
    "placed_requests", "placed_members", "peak_requests", "peak_members", "cpu_prod", "cpu_nonprod",
    "ram_tib", "nvme_tib", "ssd_tib", "hdd_tib", "gpu",
)


def read_json(path):
    with Path(path).open(encoding="utf-8") as source:
        return json.load(source)


def nonnegative(value, name, integer=False):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0:
        raise ValueError(f"{name}: expected a finite nonnegative number")
    if integer and not isinstance(value, int):
        raise ValueError(f"{name}: expected an integer")
    return value


def within(directory, relative):
    path = (directory / relative).resolve()
    if not path.is_relative_to(directory.resolve()):
        raise ValueError(f"Result path escapes run directory: {relative}")
    return path


def load_run(run_dir):
    directory = Path(run_dir)
    manifest = read_json(directory / "manifest.json")
    if manifest.get("format_version") != 1:
        raise ValueError("Unsupported manifest format_version")
    status_path = directory / "status.json"
    statuses = read_json(status_path) if status_path.exists() else {"format_version": 1, "cases": {}}
    if statuses.get("format_version") != 1 or not isinstance(statuses.get("cases"), dict):
        raise ValueError("Unsupported status.json schema")
    cells = []
    seen = set()
    for case in manifest["cases"]:
        key = case["key"]
        if key in seen:
            raise ValueError(f"Duplicate case key: {key}")
        seen.add(key)
        status = statuses["cases"].get(key)
        cell = {**case, "status": "MISSING", "data": None}
        if status is not None:
            state = status.get("status")
            if state not in STATUSES:
                raise ValueError(f"{key}: unknown status {state!r}")
            cell["status"] = state.upper()
            if state == "success":
                path = within(directory, case["result"])
                raw = path.read_bytes()
                if hashlib.sha256(raw).hexdigest() != status.get("result_sha256"):
                    raise ValueError(f"{key}: result SHA-256 does not match status.json")
                cell["data"] = json.loads(raw)
        cells.append(cell)
    return manifest, cells


def validate_result(cell, dataset, controls):
    result = cell["data"]
    if not isinstance(result, dict) or result.get("format_version") != 2:
        raise ValueError(f"{cell['alias']}: successful result must use RunCLI format 2")
    if result.get("complete") is not True:
        limit = controls["max_operations"]
        processed = nonnegative(result["processed_operations"], "processed_operations", True)
        normalized = nonnegative(result["normalized_operations"], "normalized_operations", True)
        if limit <= 0 or processed != min(limit, normalized):
            raise ValueError(f"{cell['alias']}: successful result did not complete the selected replay")
    settings = cell["settings"]
    expected = {
        "algorithm": settings["id"], "algorithm_parameters": settings["parameters"],
        "snapshot_sha256": dataset["snapshot_sha256"], "operations_sha256": dataset["operations_sha256"],
        "bulk_size": cell["bulk_size"],
        **{key: controls[key] for key in CONTROL_FIELDS},
    }
    for key, value in expected.items():
        if result.get(key) != value:
            raise ValueError(f"{cell['alias']}: result {key} differs from manifest")
    for key in COMMON_FIELDS:
        if key not in result:
            raise ValueError(f"{cell['alias']}: missing result field {key}")
    creates = result["creates"]
    attempts = nonnegative(creates["attempted_units"], "attempted_units", True)
    placed = nonnegative(creates["admitted_units"], "admitted_units", True)
    if placed > attempts:
        raise ValueError("admitted_units exceeds attempted_units")
    nonnegative(creates["admitted_resources"], "admitted_resources", True)
    nonnegative(result["peak_active_units"], "peak_active_units", True)
    nonnegative(result["peak_active_resources"], "peak_active_resources", True)
    nonnegative(result["timing_ms"]["schedule_total"], "schedule_total")
    peak = result["logical_resources"]["peak_active"]
    if not isinstance(peak, dict):
        raise ValueError("logical_resources.peak_active must be a sparse dimension map")
    for dimension, value in peak.items():
        if not isinstance(dimension, str) or not dimension.isdecimal() or int(dimension) <= 0:
            raise ValueError(f"Invalid resource dimension: {dimension!r}")
        nonnegative(value, f"peak_active[{dimension}]")
    counters = result.get("algorithm_counters", {})
    if settings["id"] == "yp-planner" and "yp_maintenance_nanos" not in counters:
        raise ValueError("yp-planner result is missing maintenance timing")
    nonnegative(counters.get("yp_maintenance_nanos", 0), "yp_maintenance_nanos")
    return result


def common_inputs(result, cross_bulk=False):
    if cross_bulk:
        fields = ("snapshot_sha256", "operations_sha256", "source_operations_digest", "normalization",
                  "dataset_id", "java", *CONTROL_FIELDS)
        return {key: result.get(key) for key in fields}
    fields = (*COMMON_FIELDS, *OPTIONAL_COMMON_FIELDS)
    values = {key: result.get(key) for key in fields}
    creates = result["creates"]
    values["attempted_units"] = creates["attempted_units"]
    values["attempted_resources"] = creates.get("attempted_resources")
    values["requested_logical"] = result["logical_resources"].get("requested")
    return values


def metrics(cell, relocation=False):
    result = cell["data"]
    row = {
        "alias": cell["alias"], "algorithm": cell["settings"]["id"],
        "parameters": cell["settings"]["parameters"], "status": cell["status"],
        "is_baseline": False, **{key: None for key in CSV_FIELDS[10:]},
    }
    if cell["status"] != "SUCCESS":
        return row
    attempts = result["creates"]["attempted_units"]
    maintenance = result.get("algorithm_counters", {}).get("yp_maintenance_nanos", 0) / 1e6 if relocation else 0
    total = result["timing_ms"]["schedule_total"] + maintenance
    peak = result["logical_resources"]["peak_active"]
    row.update(
        avg_ms=total / attempts if attempts else None,
        maintenance_ms=maintenance / attempts if attempts else None,
        placed_requests=result["creates"]["admitted_units"],
        placed_members=result["creates"]["admitted_resources"],
        peak_requests=result["peak_active_units"], peak_members=result["peak_active_resources"],
        cpu_prod=peak.get("1", 0), cpu_nonprod=peak.get("12", 0),
        ram_tib=peak.get("2", 0) / 1024 ** 2,
        nvme_tib=peak.get("5", 0) / 1024, ssd_tib=peak.get("7", 0) / 1024,
        hdd_tib=peak.get("9", 0) / 1024, gpu=peak.get("11", 0),
    )
    return row


def formatted(value, digits=1, signed=False):
    if value is None:
        return "n/a"
    value = 0.0 if round(value, digits) == 0 else value
    return format(value, f"{ '+' if signed else ''}.{digits}f")


def display_cells(row, relocation):
    label = row["alias"]
    if row["status"] != "SUCCESS":
        return [f"{label} [{row['status']}]"] + [""] * (9 if relocation else 8)
    cells = [label, formatted(row["avg_ms"], 2), formatted(row["delta_avg_pct"], 1, True)]
    if relocation:
        cells.append(formatted(row["maintenance_ms"], 2))
    cells += [f"{row['placed_requests']} / {row['placed_members']}",
              f"{row['peak_requests']} / {row['peak_members']}",
              f"{row['cpu_prod']:.0f} / {row['cpu_nonprod']:.0f}", formatted(row["ram_tib"]),
              " / ".join(formatted(row[key]) for key in ("nvme_tib", "ssd_tib", "hdd_tib")),
              formatted(row["gpu"])]
    return cells


def build_tables(manifest, cells, baseline="fitcount", baseline_bulk_size="same"):
    if baseline_bulk_size != "same":
        baseline_bulk_size = int(baseline_bulk_size)
        if baseline_bulk_size <= 0:
            raise ValueError("baseline_bulk_size must be same or a positive integer")
    datasets = {dataset["cloud"]: dataset for dataset in manifest["datasets"]}
    if len(datasets) != len(manifest["datasets"]):
        raise ValueError("Duplicate cloud in manifest")
    groups = {}
    model_hashes = {}
    for cell in cells:
        if cell["cloud"] not in datasets:
            raise ValueError(f"Unknown cloud: {cell['cloud']}")
        mode = "relocation" if cell["settings"]["id"] in RELOCATORS else "placement"
        if cell["mode"] != mode:
            raise ValueError(f"{cell['alias']}: incorrect placement/relocation mode")
        if cell["alias"] == baseline and mode == "relocation":
            raise ValueError("Baseline must be a placement algorithm")
        group = groups.setdefault((cell["cloud"], cell["bulk_size"]), [])
        if any(previous["alias"] == cell["alias"] for previous in group):
            raise ValueError(f"Duplicate alias in cloud/bulk group: {cell['alias']}")
        if cell["status"] == "SUCCESS":
            result = validate_result(cell, datasets[cell["cloud"]], manifest["controls"])
            previous = next((item["data"] for item in group if item["status"] == "SUCCESS"), None)
            if previous is not None and common_inputs(previous) != common_inputs(result):
                raise ValueError(f"{cell['cloud']} B{cell['bulk_size']}: incompatible scenario/options")
            if result.get("model_sha256") is not None:
                model = (result.get("model_feature_schema"), result["model_sha256"])
                algorithm = cell["settings"]["id"]
                if model_hashes.setdefault(algorithm, model) != model:
                    raise ValueError(f"{algorithm}: results used different models or feature schemas")
        group.append(cell)
    tables = []
    for cloud in datasets:
        for bulk_size in manifest["controls"]["bulk_sizes"]:
            group = groups.get((cloud, bulk_size), [])
            if not group:
                continue
            base_bulk = bulk_size if baseline_bulk_size == "same" else baseline_bulk_size
            base_cell = next((cell for cell in groups.get((cloud, base_bulk), [])
                              if cell["alias"] == baseline and cell["status"] == "SUCCESS"), None)
            if base_cell is not None:
                for cell in group:
                    if cell["status"] == "SUCCESS" and common_inputs(cell["data"], True) != common_inputs(base_cell["data"], True):
                        raise ValueError(f"{cloud}: baseline B{base_bulk} has incompatible inputs/options")
            for mode in ("placement", "relocation"):
                selected = [cell for cell in group if cell["mode"] == mode]
                if not selected:
                    continue
                relocation = mode == "relocation"
                if relocation:
                    selected = [cell for cell in group if cell["mode"] == mode or cell["alias"] in (baseline, "yp-light")]
                base_row = metrics(base_cell, relocation) if base_cell is not None else None
                rows = [metrics(cell, relocation) for cell in selected]
                rows.sort(key=lambda row: (row["status"] != "SUCCESS", -(row["placed_requests"] or 0)))
                for row in rows:
                    denominator = base_row["avg_ms"] if base_row is not None else None
                    if row["avg_ms"] is not None and denominator:
                        row["delta_avg_pct"] = 100 * (row["avg_ms"] / denominator - 1)
                    row["is_baseline"] = row["alias"] == baseline and bulk_size == base_bulk
                    row["cells"] = display_cells(row, relocation)
                title = f"{cloud.upper()} · B{bulk_size}"
                if relocation:
                    title += " · переселения"
                prefix = manifest["controls"]["max_operations"]
                if prefix:
                    title += f" · префикс {prefix} операций"
                delta = "Δ avg, %" if base_bulk == bulk_size else f"Δ avg к B{base_bulk}, %"
                headers = ["Алгоритм", "avg работы, мс" if relocation else "avg, мс", delta]
                if relocation:
                    headers.append("maintenance / запр., мс")
                headers += ["Размещено\nзапр. / участ.", "Пик активных\nзапр. / участ.",
                            "Пик CPU, vCPU\nprod / nonprod", "Пик RAM\nTiB",
                            "Пик дисков, TiB\nNVMe / SSD / HDD", "GPU"]
                tables.append({"cloud": cloud, "bulk_size": bulk_size, "mode": mode, "title": title,
                               "headers": headers, "rows": rows, "baseline": baseline,
                               "baseline_bulk_size": base_bulk})
    return tables


def markdown(table):
    def escape(value):
        return str(value).replace("\\", "\\\\").replace("|", "\\|").replace("\n", "<br>").replace("\r", "")
    lines = [f"### {table['title']}", "", "| " + " | ".join(map(escape, table["headers"])) + " |",
             "| " + " | ".join(["---"] + ["---:"] * (len(table["headers"]) - 1)) + " |"]
    for row in table["rows"]:
        lines.append("| " + " | ".join(map(escape, row["cells"])) + " |")
    return "\n".join(lines) + "\n"


def write_csv(table, path):
    with path.open("w", encoding="utf-8", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=CSV_FIELDS)
        writer.writeheader()
        for row in table["rows"]:
            record = {key: row.get(key) for key in CSV_FIELDS}
            record.update(cloud=table["cloud"], bulk_size=table["bulk_size"], mode=table["mode"],
                          baseline=table["baseline"], baseline_bulk_size=table["baseline_bulk_size"],
                          parameters=json.dumps(row["parameters"], ensure_ascii=False, sort_keys=True),
                          latency_measurement="schedule+maintenance ms/attempt" if table["mode"] == "relocation" else "schedule ms/attempt")
            writer.writerow(record)


def font_paths(font):
    if font is not None:
        regular = Path(font)
        if not regular.is_file():
            raise ValueError(f"Font file not found: {regular}")
        candidates = [regular.with_name(regular.stem + " Bold" + regular.suffix),
                      regular.with_name(regular.stem + "-Bold" + regular.suffix)]
        return regular, next((path for path in candidates if path.is_file()), regular)
    for regular, bold in (
        ("/System/Library/Fonts/Supplemental/Arial.ttf", "/System/Library/Fonts/Supplemental/Arial Bold.ttf"),
        ("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"),
        ("/usr/share/fonts/dejavu-sans-fonts/DejaVuSans.ttf", "/usr/share/fonts/dejavu-sans-fonts/DejaVuSans-Bold.ttf"),
        ("C:/Windows/Fonts/arial.ttf", "C:/Windows/Fonts/arialbd.ttf"),
    ):
        if Path(regular).is_file() and Path(bold).is_file():
            return Path(regular), Path(bold)
    raise ValueError("PNG needs a Cyrillic font; pass --font /path/to/DejaVuSans.ttf")


def write_png(table, path, font=None):
    try:
        from PIL import Image, ImageDraw, ImageFont
    except ImportError as failure:
        raise ValueError("PNG requires Pillow: python3 -m pip install Pillow") from failure
    regular_path, bold_path = font_paths(font)
    scale, padding, margin = 2, 9, 24
    regular = ImageFont.truetype(str(regular_path), 18 * scale)
    bold = ImageFont.truetype(str(bold_path), 18 * scale)
    title_font = ImageFont.truetype(str(bold_path), 29 * scale)
    draw = ImageDraw.Draw(Image.new("RGB", (1, 1)))
    headers, rows = table["headers"], table["rows"]
    widths = []
    for index, header in enumerate(headers):
        header_width = max(draw.textlength(line, font=bold) for line in header.splitlines())
        value_width = max((draw.textlength(row["cells"][index], font=bold if row["is_baseline"] else regular)
                           for row in rows), default=0)
        widths.append(math.ceil(max(header_width, value_width) / scale) + 2 * padding)
    title_width = math.ceil(draw.textlength(table["title"], font=title_font) / scale)
    widths[0] += max(0, title_width - sum(widths))
    line_height, row_height, title_height = 22, 26, 54
    header_height = max(len(header.splitlines()) for header in headers) * line_height + 16
    width = 2 * margin + sum(widths)
    height = margin + title_height + header_height + len(rows) * row_height + 10
    image = Image.new("RGB", (width * scale, height * scale), "white")
    draw = ImageDraw.Draw(image)
    ink, muted, grid = "#172838", "#3c5265", "#e2e8ee"
    draw.text((margin * scale, margin * scale), table["title"], font=title_font, fill=ink, anchor="lt")
    table_top = margin + title_height
    draw.rectangle((margin * scale, table_top * scale, (width - margin) * scale,
                    (table_top + header_height) * scale), fill="#eaf0f6")
    left = margin
    for index, (header, column_width) in enumerate(zip(headers, widths)):
        lines = header.splitlines()
        top = table_top + (header_height - len(lines) * line_height) / 2
        for line in lines:
            x = left + padding if index == 0 else left + column_width - padding
            draw.text((round(x * scale), round(top * scale)), line, font=bold, fill=muted,
                      anchor="lt" if index == 0 else "rt")
            top += line_height
        left += column_width
    for index, row in enumerate(rows):
        top = table_top + header_height + index * row_height
        if row["is_baseline"]:
            draw.rectangle((margin * scale, top * scale, (width - margin) * scale,
                            (top + row_height) * scale), fill="#dcecf8")
        left = margin
        for column, (value, column_width) in enumerate(zip(row["cells"], widths)):
            x = left + padding if column == 0 else left + column_width - padding
            draw.text((round(x * scale), round((top + row_height / 2) * scale)), value,
                      font=bold if row["is_baseline"] else regular, fill=ink,
                      anchor="lm" if column == 0 else "rm")
            left += column_width
        draw.line((margin * scale, (top + row_height) * scale, (width - margin) * scale,
                   (top + row_height) * scale), fill=grid, width=scale)
    image.resize((width, height), Image.Resampling.LANCZOS).save(path, optimize=True)


def write_tables(tables, output, formats, font=None):
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    paths = []
    for table in tables:
        stem = re.sub(r"[^A-Za-z0-9_.-]+", "-", table["cloud"]) + f"-B{table['bulk_size']}"
        if table["mode"] == "relocation":
            stem += "-relocations"
        for kind in dict.fromkeys(formats):
            kind = "markdown" if kind == "md" else kind
            path = output / f"{stem}.{'md' if kind == 'markdown' else kind}"
            if kind == "markdown":
                path.write_text(markdown(table), encoding="utf-8")
            elif kind == "csv":
                write_csv(table, path)
            elif kind == "png":
                write_png(table, path, font)
            else:
                raise ValueError(f"Unknown output format: {kind}")
            paths.append(path)
    return paths


def main(argv=None):
    parser = argparse.ArgumentParser(description="Render benchmark tables from run-benchmarks.py output.")
    parser.add_argument("run_dir", type=Path)
    parser.add_argument("--output", type=Path, help="output directory (default: RUN_DIR/tables)")
    parser.add_argument("--format", nargs="+", choices=("markdown", "md", "csv", "png"), default=["markdown"])
    parser.add_argument("--baseline", default="fitcount", help="baseline alias (default: fitcount)")
    parser.add_argument("--baseline-bulk-size", default="same", help="same or a positive batch size; 1 reproduces cross-B1 deltas")
    parser.add_argument("--font", type=Path, help="TrueType/OpenType font with Cyrillic glyphs for PNG")
    options = parser.parse_args(argv)
    try:
        manifest, cells = load_run(options.run_dir)
        tables = build_tables(manifest, cells, options.baseline, options.baseline_bulk_size)
        if not tables:
            raise ValueError("No benchmark cases to render")
        for path in write_tables(tables, options.output or options.run_dir / "tables", options.format, options.font):
            print(path)
        return 0
    except (OSError, ValueError, KeyError, TypeError) as failure:
        print(f"render-benchmarks: {failure}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
