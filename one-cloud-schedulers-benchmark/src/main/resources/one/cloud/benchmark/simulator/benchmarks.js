import {
    $,
    html,
    number,
    colors,
    algorithmName,
    renderPlan,
    renderReport,
    renderDetail
} from "/benchmark-views.js";

const view = {
    registry: [],
    datasets: [],
    algorithms: [],
    capabilities: {},
    report: null,
    source: "",
    rows: [],
    baseline: 0,
    operation: null,
    activeCase: 0,
    detailMode: "metrics",
    job: null,
    running: false,
    profileJobs: new Map(),
    selectedDatasets: new Set(),
    caseDataset: (item) => matchDataset(item)?.name || item.dataset.split(/[\\/]/).pop(),
    matchDataset
};
let pollTimer;
let profileCase = null;
let toastTimer;

async function api(path, body) {
    const response = await fetch(
        path,
        body === undefined
            ? {}
            : {
                  method: "POST",
                  headers: { "Content-Type": "application/json", "X-Placement-UI": "1" },
                  body: JSON.stringify(body)
              }
    );
    const result = await response.json();
    if (!response.ok) throw new Error(result.error || `HTTP ${response.status}`);
    return result;
}

function showError(error) {
    $("error").textContent = error.message;
    $("error").hidden = false;
}

async function perform(action) {
    $("error").hidden = true;
    try {
        await action();
    } catch (error) {
        showError(error);
    }
}

function toast(text) {
    $("toast").textContent = text;
    $("toast").hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => {
        $("toast").hidden = true;
    }, 6000);
}

function download(filename, value) {
    const url = URL.createObjectURL(new Blob([JSON.stringify(value, null, 2)], { type: "application/json" }));
    const link = document.createElement("a");
    link.href = url;
    link.download = filename;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function matchDataset(item) {
    return view.datasets.find(
        (dataset) =>
            item.input &&
            dataset.input.snapshotSha256 === item.input.snapshotSha256 &&
            dataset.input.operationsSha256 === item.input.operationsSha256
    );
}

function profileIdentity(item, options) {
    return JSON.stringify([
        item.input?.snapshotSha256,
        item.input?.operationsSha256,
        item.scenario?.digest,
        item.scenario?.gpuModel,
        item.scenario?.bulkSize,
        item.algorithm.id,
        Object.entries(item.algorithm.parameters).sort(([left], [right]) => left.localeCompare(right)),
        options.maxOperations,
        options.catalogueLimit,
        options.probeCap
    ]);
}

function groupKey(item) {
    return JSON.stringify([
        item.dataset,
        item.scenario?.digest,
        item.scenario?.gpuModel,
        item.scenario?.bulkSize
    ]);
}

function selectGroup() {
    view.rows = view.report.cases
        .map((item, index) => ({ item, index }))
        .filter((row) => groupKey(row.item) === $("dataset").value)
        .map((row, index) => ({
            ...row,
            label: algorithmName(row.item, view.registry),
            color: colors[index % colors.length]
        }));
    $("baseline").innerHTML = view.rows
        .map(
            ({ item, index, label }) => `<option value="${index}">${html(label)}
        · ${item.algorithm.parameters.seed === undefined ? "без seed" : "seed " + html(item.algorithm.parameters.seed)}
        · #${index + 1}</option>`
        )
        .join("");
    view.baseline = (view.rows.find((row) => row.item.algorithm.id === "fitcount") || view.rows[0]).index;
    $("baseline").value = String(view.baseline);
    const operations = [
        ...new Set(
            view.rows.flatMap(({ item }) => (item.quality?.checkpoints || []).map((point) => point.operation))
        )
    ].sort((left, right) => left - right);
    const firstFill = view.rows[0].item.quality?.inflation?.operation;
    $("checkpoint").innerHTML = operations
        .map(
            (operation) => `<option value="${operation}">Шаг ${number(operation)}
        ${operation === firstFill ? " · первое заполнение" : ""}</option>`
        )
        .join("");
    view.operation = operations.includes(firstFill) ? firstFill : (operations[0] ?? null);
    $("checkpoint").value = String(view.operation);
    renderReport(view);
}

function validateReport(report) {
    if (
        report.formatVersion !== 1 ||
        report.measurement !== "isolated-jvm-full-replay" ||
        !Array.isArray(report.cases) ||
        !report.cases.length ||
        !report.cases.every(
            (item) =>
                typeof item.dataset === "string" &&
                typeof item.algorithm?.id === "string" &&
                item.algorithm.parameters &&
                typeof item.algorithm.parameters === "object" &&
                (item.scenario?.digest || item.qualityError || item.forks?.some((fork) => fork.error))
        )
    ) {
        throw new Error(
            "Нужен JSON-отчёт команды benchmark: formatVersion=1, isolated-jvm-full-replay, cases."
        );
    }
}

function loadReport(report, source) {
    validateReport(report);
    view.report = report;
    view.source = source;
    view.profileJobs.clear();
    profileCase = null;
    if ($("detail").open) $("detail").close();
    const groups = [...new Map(report.cases.map((item) => [groupKey(item), item])).entries()];
    $("dataset").innerHTML = groups
        .map(
            ([key, item]) => `<option value="${html(key)}">${html(view.caseDataset(item))}
        · B=${html(item.scenario?.bulkSize ?? "н/д")} · ${html(item.scenario?.gpuModel ?? "н/д")}</option>`
        )
        .join("");
    $("empty-state").hidden = true;
    $("report-content").hidden = false;
    $("export-report").disabled = false;
    selectGroup();
}

function renderDatasets() {
    $("input-datasets").innerHTML = view.datasets.length
        ? view.datasets
              .map(
                  (dataset) =>
                      `<label class="dataset-item"><input type="checkbox" data-dataset="${html(dataset.id)}"
        ${view.selectedDatasets.has(dataset.id) ? "checked" : ""}>
        <span title="${html(dataset.input.snapshotSha256)}">${html(dataset.name)}</span></label>`
              )
              .join("")
        : '<p class="muted">Датасеты ещё не загружены.</p>';
}

function renderJob() {
    const job = view.job;
    view.running = job && ["running", "cancelling"].includes(job.status);
    $("launch-benchmark").disabled = !!view.running;
    $("import-button").disabled = !!view.running;
    $("job-panel").hidden = !job;
    if (!job) return;
    const labels = {
        running: "Выполняется",
        cancelling: "Останавливаем процессы…",
        cancelled: "Отменён",
        complete: "Готово",
        partial: "Есть результаты и ошибки",
        failed: "Ошибка"
    };
    $("job-title").textContent = `${job.kind === "profile" ? "Профиль" : "Бенчмарк"} #${job.id}`;
    $("job-summary").textContent = labels[job.status] || job.status;
    $("job-progress").textContent = job.error || job.progress || "Подготовка…";
    $("cancel-job").hidden = !view.running;
    $("cancel-job").disabled = job.status === "cancelling";
}

async function pollJob() {
    clearTimeout(pollTimer);
    try {
        const job = await api(`/api/experiments/job?id=${encodeURIComponent(view.job.id)}`);
        view.job = job;
        if (job.kind === "profile" && profileCase !== null) {
            const saved = view.profileJobs.get(profileCase) || {};
            view.profileJobs.set(profileCase, { ...saved, ...job });
        }
        renderJob();
        if (job.kind === "benchmark" && !view.running && job.report) {
            loadReport(job.report, `Бенчмарк #${job.id}`);
        }
        if ($("detail").open && view.detailMode === "profile") renderDetail(view);
        if (view.running) pollTimer = setTimeout(pollJob, 1000);
        else if (job.error) showError(new Error(job.error));
    } catch (error) {
        showError(error);
        pollTimer = setTimeout(pollJob, 3000);
    }
}

function numeric(id, key) {
    const input = $(id);
    const value = Number(input.value);
    if (!input.value.trim() || !input.checkValidity() || !Number.isSafeInteger(value)) {
        throw new Error(`Проверьте параметр ${key}.`);
    }
    return value;
}

function experimentConfig() {
    const datasets = [...view.selectedDatasets];
    if (!datasets.length) throw new Error("Загрузите и выберите хотя бы один датасет.");
    if (!view.algorithms.length) throw new Error("Добавьте хотя бы один алгоритм.");
    for (const input of document.querySelectorAll("#config-algorithms input")) {
        if (!input.checkValidity()) throw new Error(`Проверьте параметр ${input.dataset.key}.`);
    }
    const config = { datasets, algorithms: structuredClone(view.algorithms), gpuModel: $("gpu").value };
    for (const [id, key] of [
        ["forks", "forks"],
        ["warmups", "warmups"],
        ["measurements", "measurements"],
        ["timeout", "timeoutSeconds"],
        ["bulk", "bulkSize"],
        ["catalogue", "catalogueLimit"],
        ["operations", "maxOperations"],
        ["probe-cap", "probeCap"],
        ["inflation", "inflationAttempts"],
        ["inflation-seed", "inflationSeed"]
    ]) {
        config[key] = numeric(id, key);
    }
    return config;
}

async function startBenchmark() {
    if (view.running) return;
    const config = experimentConfig();
    $("launch-benchmark").disabled = true;
    try {
        view.job = await api("/api/experiments/run", config);
        renderJob();
        pollTimer = setTimeout(pollJob, 300);
    } finally {
        $("launch-benchmark").disabled = !!view.running;
    }
}

function openDetail(index, mode = "metrics") {
    view.activeCase = index;
    view.detailMode = mode;
    renderDetail(view);
    $("detail").showModal();
}

async function startProfile() {
    const item = view.report.cases[view.activeCase];
    const dataset = matchDataset(item);
    if (!dataset) throw new Error("Сначала загрузите исходный датасет из этого отчёта.");
    const event = $("profile-event").value;
    const format = $("profile-format").value;
    const options = view.report.options;
    const request = {
        datasetId: dataset.id,
        algorithm: item.algorithm,
        input: item.input,
        scenario: item.scenario,
        options: {
            maxOperations: options.maxOperations,
            catalogueLimit: options.catalogueLimit,
            probeCap: options.probeCap
        },
        timeoutSeconds: numeric("timeout", "timeoutSeconds"),
        event,
        format
    };
    $("collect-profile").disabled = true;
    try {
        view.job = await api("/api/experiments/profile", request);
        profileCase = view.activeCase;
        view.profileJobs.set(profileCase, { ...view.job, event, format });
        renderJob();
        renderDetail(view);
        pollTimer = setTimeout(pollJob, 300);
    } catch (error) {
        $("collect-profile").disabled = false;
        throw error;
    }
}

async function upload(event) {
    event.preventDefault();
    $("upload-submit").disabled = true;
    $("upload-error").textContent = "";
    try {
        const snapshot = $("snapshot-file").files[0];
        const operations = $("operations-file").files[0];
        if (!snapshot || !operations || snapshot.size === 0 || operations.size === 0) {
            throw new Error("Выберите оба непустых файла.");
        }
        if (snapshot.size + operations.size > 128 * 1024 * 1024)
            throw new Error("Пара файлов превышает 128 МиБ.");
        const response = await fetch("/api/experiments/datasets", {
            method: "POST",
            headers: {
                "X-Placement-UI": "1",
                "X-Snapshot-Length": String(snapshot.size),
                "X-Dataset-Name": encodeURIComponent($("upload-name").value.trim())
            },
            body: new Blob([snapshot, operations])
        });
        const dataset = await response.json();
        if (!response.ok) throw new Error(dataset.error || `HTTP ${response.status}`);
        view.datasets.push(dataset);
        view.selectedDatasets.add(dataset.id);
        renderDatasets();
        $("upload-dialog").close();
        toast(`Датасет ${dataset.name} загружен.`);
        if ($("detail").open) renderDetail(view);
    } catch (error) {
        $("upload-error").textContent = error.message;
    } finally {
        $("upload-submit").disabled = false;
    }
}

function bindControls() {
    for (const id of ["upload-dataset", "empty-upload"]) $(id).onclick = () => $("upload-dialog").showModal();
    $("upload-form").onsubmit = upload;
    $("input-datasets").onchange = (event) => {
        const id = event.target.dataset.dataset;
        if (event.target.checked) view.selectedDatasets.add(id);
        else view.selectedDatasets.delete(id);
    };
    $("launch-benchmark").onclick = () => perform(startBenchmark);
    $("cancel-job").onclick = () =>
        perform(async () => {
            view.job = await api("/api/experiments/cancel", { id: view.job.id });
            renderJob();
        });
    $("dataset").onchange = selectGroup;
    $("baseline").onchange = () => {
        view.baseline = Number($("baseline").value);
        renderReport(view);
    };
    $("checkpoint").onchange = () => {
        view.operation = Number($("checkpoint").value);
        renderReport(view);
    };
    $("results").onclick = (event) => {
        const detail = event.target.closest("[data-open]");
        const profile = event.target.closest("[data-profile]");
        if (detail) openDetail(Number(detail.dataset.open));
        if (profile) openDetail(Number(profile.dataset.profile), "profile");
    };
    $("detail-body").onclick = (event) => {
        if (event.target.closest("#collect-profile")) perform(startProfile);
    };
    for (const mode of ["metrics", "profile"])
        $("detail-" + mode + "-tab").onclick = () => {
            view.detailMode = mode;
            renderDetail(view);
        };
    for (const button of document.querySelectorAll("[data-close]")) {
        button.onclick = () => $(button.dataset.close).close();
    }
    for (const button of document.querySelectorAll("[data-tab]"))
        button.onclick = () => {
            for (const tab of document.querySelectorAll("[data-tab]"))
                tab.classList.toggle("active", tab === button);
            for (const tab of ["resources", "headroom", "method"])
                $(tab + "-view").hidden = tab !== button.dataset.tab;
        };
    $("export-report").onclick = () => download("benchmark-results.json", view.report);
    $("export-config").onclick = () =>
        perform(async () => {
            const config = experimentConfig();
            config.datasets = config.datasets.map(
                (id) => view.datasets.find((dataset) => dataset.id === id).directory
            );
            download("benchmark-config.json", config);
            toast(
                "Перед запуском CLI укажите пути к датасетам в скачанном JSON. Команда: ./placement-bench benchmark --config benchmark-config.json"
            );
        });
    $("import-button").onclick = () => $("report-file").click();
    $("report-file").onchange = () =>
        perform(async () => {
            const file = $("report-file").files[0];
            try {
                if (!file) return;
                if (file.size > 64 * 1024 * 1024) throw new Error("Отчёт превышает 64 МиБ.");
                loadReport(JSON.parse(await file.text()), `Импорт: ${file.name}`);
            } finally {
                $("report-file").value = "";
            }
        });
    $("add-algorithm").onclick = () => {
        $("algorithm-choice").innerHTML = view.registry
            .map(
                (algorithm) => `<option value="${html(algorithm.id)}"
            ${algorithm.requiresPrior ? "disabled" : ""}>${html(algorithm.name)}
            ${algorithm.requiresPrior ? " — нужен prior, используйте CLI" : ""}</option>`
            )
            .join("");
        $("algorithm-dialog").showModal();
    };
    $("confirm-add").onclick = () => {
        const descriptor = view.registry.find((algorithm) => algorithm.id === $("algorithm-choice").value);
        view.algorithms.push({
            id: descriptor.id,
            parameters: Object.fromEntries(
                descriptor.parameters.map((parameter) => [parameter.key, parameter.defaultValue])
            )
        });
        $("algorithm-dialog").close();
        renderPlan(view);
    };
    $("config-algorithms").onchange = (event) => {
        const input = event.target;
        if (!input.dataset.key) return;
        const algorithm = view.algorithms[Number(input.dataset.plan)];
        const parameter = view.registry
            .find((descriptor) => descriptor.id === algorithm.id)
            .parameters.find((parameter) => parameter.key === input.dataset.key);
        algorithm.parameters[parameter.key] = ["INTEGER", "NUMBER"].includes(parameter.type)
            ? Number(input.value)
            : parameter.type === "BOOLEAN"
              ? input.value === "true"
              : input.value;
    };
    $("config-algorithms").onclick = (event) => {
        const remove = event.target.closest("[data-remove]");
        if (remove) {
            view.algorithms.splice(Number(remove.dataset.remove), 1);
            renderPlan(view);
        }
    };
}

async function initialize() {
    const [registry, workspace] = await Promise.all([api("/api/algorithms"), api("/api/experiments")]);
    view.registry = registry.algorithms;
    view.datasets = workspace.datasets;
    view.capabilities = workspace;
    for (const dataset of view.datasets) view.selectedDatasets.add(dataset.id);
    for (const id of ["fitcount", "randomn-fitcount", "rl-top1", "rl-topn-fitcount"]) {
        const descriptor = view.registry.find((algorithm) => algorithm.id === id);
        if (descriptor)
            view.algorithms.push({
                id,
                parameters: Object.fromEntries(
                    descriptor.parameters.map((parameter) => [parameter.key, parameter.defaultValue])
                )
            });
    }
    renderDatasets();
    renderPlan(view);
    bindControls();
    if (workspace.benchmark?.report)
        loadReport(workspace.benchmark.report, `Бенчмарк #${workspace.benchmark.id}`);
    if (view.report) {
        for (const job of workspace.profiles || []) {
            if (!job.profile) continue;
            const identity = profileIdentity(job.profile, job.profile.options);
            view.report.cases.forEach((item, index) => {
                if (profileIdentity(item, view.report.options) === identity) {
                    view.profileJobs.set(index, {
                        ...job,
                        event: job.profile.event,
                        format: job.profile.format
                    });
                }
            });
        }
    }
    view.job = workspace.job;
    renderJob();
    if (view.running) {
        if (view.job.kind === "profile" && view.report) {
            profileCase =
                [...view.profileJobs.entries()].find(([, job]) => job.id === view.job.id)?.[0] ?? null;
        }
        pollTimer = setTimeout(pollJob, 300);
    }
}

perform(initialize);
