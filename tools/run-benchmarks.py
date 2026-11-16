#!/usr/bin/env python3
"""Run an ordered benchmark matrix in fresh JVMs and retain each cell independently."""

import argparse
from datetime import datetime, timezone
import fcntl
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import time


JVM_ARGS = ["-Xms256m", "-Xmx6g", "--enable-native-access=ALL-UNNAMED"]
MAIN_CLASS = "one.cloud.benchmark.cli.RunnerCli"
RELOCATORS = {"yp-planner", "reactive-ilp", "proactive-ilp"}
NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]*\Z")


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def read_json(path):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError(f"Duplicate JSON key: {key}")
            result[key] = value
        return result

    def invalid(value):
        raise ValueError(f"Invalid JSON number: {value}")

    with Path(path).open(encoding="utf-8") as source:
        return json.load(source, object_pairs_hook=pairs, parse_constant=invalid)


def write_json(path, value):
    path = Path(path)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, ensure_ascii=False, allow_nan=False) + "\n", encoding="utf-8")
    temporary.replace(path)


def unique_names(values, label):
    seen = set()
    for value in values:
        if not isinstance(value, str) or not NAME.fullmatch(value):
            raise ValueError(f"Invalid {label}: {value!r}; use letters, digits, dots, hyphens or underscores")
        if value.casefold() in seen:
            raise ValueError(f"Duplicate {label} (ignoring case): {value}")
        seen.add(value.casefold())


def load_algorithms(algorithm_ids, algorithms_file):
    if algorithm_ids is not None:
        entries = {value: {"id": value} for value in algorithm_ids}
        unique_names(algorithm_ids, "algorithm alias")
        base = Path.cwd()
    else:
        algorithms_file = Path(algorithms_file).resolve(strict=True)
        entries = read_json(algorithms_file)
        base = algorithms_file.parent
    if not isinstance(entries, dict) or not entries:
        raise ValueError("Algorithms file must be a nonempty object mapping aliases to algorithm selections")
    unique_names(entries, "algorithm alias")
    result = []
    for alias, selection in entries.items():
        if not isinstance(selection, dict):
            raise ValueError(f"Algorithm {alias}: selection must be an object")
        selection = dict(selection)
        if "rolloutPrior" in selection and selection["rolloutPrior"] is not None:
            prior = selection["rolloutPrior"]
            if not isinstance(prior, str) or not prior:
                raise ValueError(f"Algorithm {alias}: rolloutPrior must be a file path")
            path = (base / prior).resolve(strict=True)
            if not path.is_file():
                raise ValueError(f"Algorithm {alias}: prior is not a file: {path}")
            selection["rolloutPrior"] = str(path)
        result.append({"alias": alias, "selection": selection})
    return result


def resolve_datasets(root, clouds, subdir):
    root = Path(root).resolve(strict=True)
    if not root.is_dir():
        raise ValueError(f"Datasets root is not a directory: {root}")
    unique_names(clouds, "cloud")
    if Path(subdir).is_absolute() or ".." in Path(subdir).parts:
        raise ValueError("--dataset-subdir must be a relative path inside each cloud")
    result = []
    for cloud in clouds:
        directory = root / cloud.lower()
        nested = directory / subdir
        if nested.is_dir():
            directory = nested
        elif not all((directory / name).is_file() for name in ("snapshot.pb", "operations.pb")):
            raise ValueError(f"Cloud {cloud}: expected snapshot.pb and operations.pb in {nested} or {directory}")
        directory = directory.resolve(strict=True)
        hashes = {}
        for name in ("snapshot", "operations"):
            path = directory / (name + ".pb")
            if not path.is_file():
                raise ValueError(f"Cloud {cloud}: missing file {path}")
            hashes[name + "_sha256"] = sha256(path)
        result.append({"cloud": cloud.lower(), "path": str(directory), **hashes})
    return result


def source_fingerprint(repository):
    paths = list((repository / "one-cloud-schedulers-benchmark/src/main").rglob("*"))
    paths += list((repository / "gradle").rglob("*"))
    paths += [repository / name for name in ("settings.gradle.kts", "build.gradle.kts", "gradle.properties", "gradlew",
                                            "one-cloud-schedulers-benchmark/build.gradle.kts", "tools/run-benchmarks.py")]
    digest = hashlib.sha256()
    for path in sorted({path for path in paths if path.is_file()}):
        digest.update(path.relative_to(repository).as_posix().encode() + b"\0")
        digest.update(sha256(path).encode() + b"\n")
    return digest.hexdigest()


def signal_process_group(group, signum):
    try:
        os.killpg(group, signum)
    except ProcessLookupError:
        pass
    except PermissionError:
        if sys.platform != "darwin":
            raise
        # Darwin reports EPERM for groups containing only zombies.
        result = subprocess.run(["/bin/ps", "-axo", "pgid=,stat="], capture_output=True, text=True, check=True)
        for line in result.stdout.splitlines():
            pgid, state = line.split()
            if int(pgid) == group and not state.startswith("Z"):
                raise


def stop_process(process):
    signal_process_group(process.pid, signal.SIGTERM)
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        pass
    finally:
        # The parent may exit before its solver child, or cleanup may be interrupted.
        signal_process_group(process.pid, signal.SIGKILL)
        process.wait()


def invoke(command, stdout, stderr, timeout=0, cwd=None):
    process = subprocess.Popen(command, cwd=cwd, stdout=stdout, stderr=stderr, start_new_session=True)
    try:
        return process.wait(timeout=timeout or None)
    finally:
        stop_process(process)


def runtime(repository, output, resume):
    fingerprint = source_fingerprint(repository)
    if not resume:
        runtime_file = output / "runtime" / "toolchain.txt"
        runtime_file.parent.mkdir()
        print("Building benchmark distribution once...", flush=True)
        with (output / "build.log").open("wb") as log:
            status = invoke([str(repository / "gradlew"), "--no-daemon", ":one-cloud-schedulers-benchmark:installDist",
                             ":one-cloud-schedulers-benchmark:writePlacementDatasetRuntime",
                             f"-PdatasetRuntimeFile={runtime_file}"], log, subprocess.STDOUT, cwd=repository)
        if status != 0:
            raise ValueError(f"Gradle failed (exit {status}); see {output / 'build.log'}")
        java = Path(runtime_file.read_text(encoding="utf-8").splitlines()[0]).resolve(strict=True)
        installed = repository / "one-cloud-schedulers-benchmark/build/install/one-cloud-schedulers-benchmark/lib"
        jars = sorted(installed.glob("*.jar"))
        if not jars:
            raise ValueError(f"No installed runtime JARs in {installed}")
        library = output / "runtime/lib"
        library.mkdir()
        for jar in jars:
            shutil.copyfile(jar, library / jar.name)
    else:
        java = Path(read_json(output / "manifest.json")["runtime"]["java"]).resolve(strict=True)
    version = subprocess.run([str(java), "-version"], capture_output=True, text=True, check=True)
    java_version = (version.stdout + version.stderr).strip()
    if not re.search(r'version "25(?:[.\-+\"]|$)|openjdk 25(?:[.\-+ ]|$)', java_version):
        raise ValueError(f"Expected Java 25 toolchain, got: {java_version}")
    jars = [{"name": path.name, "sha256": sha256(path)} for path in sorted((output / "runtime/lib").glob("*.jar"))]
    if not jars:
        raise ValueError("Frozen runtime JARs are missing")
    if source_fingerprint(repository) != fingerprint:
        raise ValueError("Benchmark sources changed while building/freezing the runtime; use a new output directory")
    return {"java": str(java), "java_sha256": sha256(java), "java_version": java_version,
            "jvm_args": JVM_ARGS, "jars": jars, "source_fingerprint": fingerprint}


def java_command(effective_runtime, output):
    return [effective_runtime["java"], *effective_runtime["jvm_args"], "-cp", str(output / "runtime/lib/*"), MAIN_CLASS]


def preflight(output, datasets, algorithms, controls, effective_runtime):
    plans = {}
    for bulk in controls["bulk_sizes"]:
        config = {"datasets": [dataset["path"] for dataset in datasets],
                  "algorithms": [entry["selection"] for entry in algorithms], "seeds": [controls["seed"]],
                  "forks": 1, "warmups": 0, "measurements": 1, "inflationAttempts": 0,
                  "bulkSize": bulk, "maxOperations": controls["max_operations"],
                  "catalogueLimit": controls["catalogue_limit"], "probeCap": controls["probe_cap"],
                  "catalogueWeights": controls["catalogue_weights"], "gpuModel": controls["gpu_model"]}
        config_path = output / f"preflight-B{bulk}.config.json"
        plan_path = output / f"preflight-B{bulk}.json"
        log_path = output / f"preflight-B{bulk}.log"
        write_json(config_path, config)
        with plan_path.open("wb") as response, log_path.open("wb") as log:
            status = invoke([*java_command(effective_runtime, output), "benchmark", "--validate-config", str(config_path)],
                            response, log)
        if status != 0:
            raise ValueError(f"B{bulk} configuration rejected; see {log_path}")
        plan = read_json(plan_path)
        if len(plan["algorithms"]) != len(algorithms):
            raise ValueError(f"B{bulk}: aliases normalize to duplicate algorithm configurations; remove duplicate aliases")
        plans[bulk] = plan["algorithms"]
    return plans


def make_cases(datasets, algorithms, normalized_by_bulk):
    cases = []
    for dataset in datasets:
        for bulk, selections in normalized_by_bulk.items():
            for entry, selection in zip(algorithms, selections):
                key = f"{dataset['cloud']}--B{bulk}--{entry['alias']}"
                prior = selection.get("prior")
                settings = selection["settings"]
                cases.append({"key": key, "cloud": dataset["cloud"], "bulk_size": bulk,
                              "alias": entry["alias"], "mode": "relocation" if settings["id"] in RELOCATORS else "placement",
                              "settings": settings,
                              "rollout_prior": None if prior is None else {"path": prior, "sha256": sha256(prior)},
                              "result": f"results/{key}.json", "log": f"logs/{key}.log"})
    unique_names([case["key"] for case in cases], "case filename")
    return cases


def validate_result(path, case, dataset, controls):
    result = read_json(path)
    expected = {"format_version": 2, "algorithm": case["settings"]["id"],
                "algorithm_parameters": case["settings"]["parameters"], "bulk_size": case["bulk_size"],
                "snapshot_sha256": dataset["snapshot_sha256"], "operations_sha256": dataset["operations_sha256"]}
    expected.update({name: controls[name] for name in ("probe_cap", "max_operations", "catalogue_limit",
                                                      "catalogue_weights", "gpu_model")})
    if not isinstance(result, dict) or any(result.get(key) != value for key, value in expected.items()):
        raise ValueError("Result metadata does not match the requested cell")
    if not isinstance(result.get("complete"), bool):
        raise ValueError("Result lacks a valid completion flag")
    for name in ("creates", "timing_ms", "logical_resources"):
        if not isinstance(result.get(name), dict):
            raise ValueError(f"Result lacks {name}")
    for name in ("attempted_units", "admitted_units", "admitted_resources"):
        value = result["creates"].get(name)
        if type(value) is not int or value < 0:
            raise ValueError(f"Invalid result creates.{name}")
    if not isinstance(result["logical_resources"].get("peak_active"), dict):
        raise ValueError("Result lacks logical_resources.peak_active")
    value = result["timing_ms"].get("schedule_total")
    if type(value) not in (int, float) or not math.isfinite(value) or value < 0:
        raise ValueError("Invalid result timing_ms.schedule_total")
    return result


def replay_complete(result, controls):
    limit = controls["max_operations"]
    if result.get("max_operations") != limit:
        return False
    if result.get("complete") is True:
        return True
    processed = result.get("processed_operations")
    normalized = result.get("normalized_operations")
    return (limit > 0 and type(processed) is int and type(normalized) is int
            and normalized >= 0 and processed == min(limit, normalized))


def run_command(effective_runtime, output, case, dataset, controls):
    command = [*java_command(effective_runtime, output), "run", "--dataset", dataset["path"],
               "--algorithm", case["settings"]["id"], "--bulk-size", str(case["bulk_size"]),
               "--probe-cap", str(controls["probe_cap"]), "--gpu-model", controls["gpu_model"],
               "--catalogue-weights", controls["catalogue_weights"]]
    for option in ("catalogue_limit", "max_operations"):
        if controls[option]:
            command += ["--" + option.replace("_", "-"), str(controls[option])]
    for key, value in case["settings"]["parameters"].items():
        text = value if isinstance(value, str) else json.dumps(value, allow_nan=False)
        command += ["--param", f"{key}={text}"]
    if case["rollout_prior"] is not None:
        command += ["--rollout-prior", case["rollout_prior"]["path"]]
    return command


def now():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def run_case(output, case, dataset, controls, effective_runtime, statuses):
    key = case["key"]
    state = {"status": "running", "started_at": now()}
    statuses["cases"][key] = state
    write_json(output / "status.json", statuses)
    result_path = output / case["result"]
    result_path.unlink(missing_ok=True)
    temporary = result_path.with_suffix(".json.tmp")
    started = time.monotonic()
    try:
        prior = case["rollout_prior"]
        if prior is not None and sha256(prior["path"]) != prior["sha256"]:
            raise ValueError("Rollout prior changed since preflight")
        with temporary.open("wb") as response, (output / case["log"]).open("wb") as log:
            code = invoke(run_command(effective_runtime, output, case, dataset, controls), response, log,
                          timeout=controls["timeout_seconds"])
        state["exit_code"] = code
        if code != 0:
            raise ValueError(f"JVM exit {code}; see {case['log']}")
        result = validate_result(temporary, case, dataset, controls)
        if prior is not None and sha256(prior["path"]) != prior["sha256"]:
            raise ValueError("Rollout prior changed during replay")
        temporary.replace(result_path)
        state.update(status="success" if replay_complete(result, controls) else "partial", result_sha256=sha256(result_path))
    except subprocess.TimeoutExpired:
        state.update(status="timeout", error=f"Exceeded {controls['timeout_seconds']} seconds")
    except KeyboardInterrupt:
        state.update(status="interrupted", error="Interrupted")
        raise
    except (OSError, ValueError) as failure:
        state.update(status="error", error=str(failure))
    finally:
        state.update(wall_seconds=round(time.monotonic() - started, 3), finished_at=now())
        write_json(output / "status.json", statuses)
    return state


def nonnegative(value):
    parsed = int(value)
    if parsed < 0:
        raise argparse.ArgumentTypeError("must be nonnegative")
    return parsed


def arguments(argv):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--datasets-root", type=Path, required=True)
    parser.add_argument("--clouds", nargs="+", required=True)
    parser.add_argument("--dataset-subdir", default="mixed-v2")
    algorithms = parser.add_mutually_exclusive_group()
    algorithms.add_argument("--algorithms", nargs="+")
    algorithms.add_argument("--algorithms-file", type=Path)
    parser.add_argument("--bulk-sizes", type=int, choices=range(1, 101), nargs="+", default=[1, 100], metavar="N")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--seed", type=int, default=44)
    parser.add_argument("--catalogue-limit", type=nonnegative, default=32)
    parser.add_argument("--probe-cap", type=nonnegative, default=0)
    parser.add_argument("--max-operations", type=nonnegative, default=0)
    parser.add_argument("--catalogue-weights", choices=("sampled", "full"), default="sampled")
    parser.add_argument("--gpu-model", choices=("devices20", "aggregate"), default="devices20")
    parser.add_argument("--timeout-seconds", type=nonnegative, default=0, help="per-cell wall-clock limit; 0 means unlimited")
    parser.add_argument("--resume", action="store_true", help="validate the manifest and reuse only complete successful cells")
    return parser.parse_args(argv)


def run(args):
    repository = Path(__file__).resolve().parent.parent
    algorithms = load_algorithms(args.algorithms, args.algorithms_file or repository / "tools/benchmark-algorithms.json")
    datasets = resolve_datasets(args.datasets_root, args.clouds, args.dataset_subdir)
    if len(set(args.bulk_sizes)) != len(args.bulk_sizes):
        raise ValueError("Duplicate bulk size")
    controls = {name: getattr(args, name) for name in ("seed", "bulk_sizes", "catalogue_limit", "probe_cap", "max_operations",
                                                     "catalogue_weights", "gpu_model", "timeout_seconds")}
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    with (output / ".lock").open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ValueError(f"Another benchmark process owns {output}") from None
        if args.resume:
            previous = read_json(output / "manifest.json")
            if previous.get("format_version") != 1 or previous.get("controls") != controls or previous.get("datasets") != datasets:
                raise ValueError("Resume rejected: inputs or options changed; use a new output directory")
            if previous["runtime"]["source_fingerprint"] != source_fingerprint(repository):
                raise ValueError("Resume rejected: benchmark sources or runner changed; use a new output directory")
        elif any(path.name != ".lock" for path in output.iterdir()):
            raise ValueError(f"Output is not empty: {output}; choose a new directory or use --resume")
        effective_runtime = runtime(repository, output, args.resume)
        if args.resume and any(previous["runtime"].get(key) != value for key, value in effective_runtime.items()):
            raise ValueError("Resume rejected: frozen runtime or Java changed; use a new output directory")
        normalized = preflight(output, datasets, algorithms, controls, effective_runtime)
        cases = make_cases(datasets, algorithms, normalized)
        if any(case["settings"]["id"] == "smt" for case in cases):
            z3 = shutil.which("z3")
            if z3 is None:
                raise ValueError("The selected smt algorithm requires z3 on PATH")
            effective_runtime["z3"] = {"path": str(Path(z3).resolve()), "sha256": sha256(z3)}
        manifest = {"format_version": 1, "controls": controls, "datasets": datasets,
                    "cases": cases, "runtime": effective_runtime}
        if args.resume:
            if manifest != previous:
                raise ValueError("Resume rejected: inputs, algorithms, options or frozen runtime changed; use a new output directory")
            statuses = read_json(output / "status.json")
            if statuses.get("format_version") != 1 or not isinstance(statuses.get("cases"), dict):
                raise ValueError("Invalid status.json")
        else:
            write_json(output / "manifest.json", manifest)
            statuses = {"format_version": 1, "cases": {}}
            write_json(output / "status.json", statuses)
        for name in ("results", "logs"):
            (output / name).mkdir(exist_ok=True)
        by_cloud = {dataset["cloud"]: dataset for dataset in datasets}
        failed = False
        for index, case in enumerate(cases, 1):
            old = statuses["cases"].get(case["key"], {})
            if old.get("status") == "success":
                try:
                    path = output / case["result"]
                    if old.get("result_sha256") != sha256(path):
                        raise ValueError("checksum mismatch")
                    if not replay_complete(validate_result(path, case, by_cloud[case["cloud"]], controls), controls):
                        raise ValueError("incomplete result")
                    print(f"[{index}/{len(cases)}] {case['key']}: reused", flush=True)
                    continue
                except (OSError, ValueError) as failure:
                    print(f"[{index}/{len(cases)}] {case['key']}: invalid saved result ({failure}); rerunning", flush=True)
            print(f"[{index}/{len(cases)}] {case['key']}: running", flush=True)
            state = run_case(output, case, by_cloud[case["cloud"]], controls, effective_runtime, statuses)
            failed |= state["status"] != "success"
            print(f"  {state['status']} ({state['wall_seconds']:.3f}s)" +
                  (f": {state['error']}" if "error" in state else ""), flush=True)
        return 1 if failed else 0


def interrupted(*_):
    raise KeyboardInterrupt


def main(argv=None):
    args = arguments(argv)
    signal.signal(signal.SIGTERM, interrupted)
    try:
        return run(args)
    except KeyboardInterrupt:
        print("Benchmark interrupted; --resume will rerun the unfinished cell.", file=sys.stderr)
        return 130
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as failure:
        print(f"Benchmark failed: {failure}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
