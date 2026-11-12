export const $ = (id) => document.getElementById(id);
export const colors = ["#85b9b0", "#93b0cc", "#c8ad77", "#a6b88a", "#c49a74", "#b0b9c4"];

export function html(value) {
    return String(value ?? "").replace(
        /[&<>"']/g,
        (character) =>
            ({
                "&": "&amp;",
                "<": "&lt;",
                ">": "&gt;",
                '"': "&quot;",
                "'": "&#39;"
            })[character]
    );
}

export function number(value, digits = 0) {
    return typeof value === "number" && Number.isFinite(value)
        ? value.toLocaleString("ru-RU", { minimumFractionDigits: digits, maximumFractionDigits: digits })
        : "н/д";
}

export const ms = (value) => (Number.isFinite(value) ? number(value / 1e6, 3) : "н/д");
export const percent = (value) => (Number.isFinite(value) ? `${number(value * 100, 2)}%` : "н/д");
export const caseErrors = (item) =>
    [item.qualityError, ...(item.forks || []).map((fork) => fork.error)].filter(Boolean);
export const samples = (item) =>
    (item.forks || []).flatMap((fork) =>
        (fork.measurements || []).map((measurement, index) => ({
            ...measurement,
            fork: fork.fork,
            index: index + 1
        }))
    );
export const point = (item, operation) =>
    item.quality?.checkpoints?.find((value) => value.operation === operation);

export function mean(item) {
    const values = samples(item)
        .filter((sample) => sample.scheduleWindows?.count > 0)
        .map((sample) => sample.scheduleWindows.totalNanos / sample.scheduleWindows.count)
        .filter(Number.isFinite)
        .sort((left, right) => left - right);
    return values.length
        ? (values[Math.floor((values.length - 1) / 2)] + values[Math.floor(values.length / 2)]) / 2
        : null;
}

export function algorithmName(item, registry) {
    const label = registry.find((algorithm) => algorithm.id === item.algorithm.id)?.name || item.algorithm.id;
    const topN = item.algorithm.parameters.topN;
    return topN === undefined ? label : `${label} · N=${topN}`;
}

function delta(value, baseline) {
    if (!Number.isFinite(value) || !Number.isFinite(baseline)) return "н/д";
    return `${value > baseline ? "+" : ""}${number(value - baseline)}`;
}

function pairs(values) {
    return `<dl>${Object.entries(values)
        .map(([key, value]) => `<div><dt>${html(key)}</dt><dd>${html(value)}</dd></div>`)
        .join("")}</dl>`;
}

export function renderPlan(view) {
    $("algorithm-count").textContent = view.algorithms.length;
    $("config-algorithms").innerHTML = view.algorithms
        .map((algorithm, index) => {
            const descriptor = view.registry.find((value) => value.id === algorithm.id);
            const fields = descriptor.parameters
                .map((parameter) => {
                    const value = algorithm.parameters[parameter.key] ?? parameter.defaultValue;
                    const attributes = `data-plan="${index}" data-key="${html(parameter.key)}"
                title="${html(parameter.description)}"`;
                    const input =
                        parameter.type === "BOOLEAN"
                            ? `<select ${attributes}><option value="true" ${value ? "selected" : ""}>true</option>
                    <option value="false" ${!value ? "selected" : ""}>false</option></select>`
                            : `<input ${attributes} value="${html(value)}"
                    ${
                        ["INTEGER", "NUMBER"].includes(parameter.type)
                            ? `type="number" step="${parameter.type === "INTEGER" ? 1 : "any"}"
                            min="${parameter.minimum}" max="${parameter.maximum}" required`
                            : ""
                    }
                    ${parameter.key === "model" ? 'readonly aria-label="Встроенная модель"' : ""}>`;
                    return `<div class="field"><label title="${html(parameter.description)}">${html(parameter.label)}</label>
                ${input}${parameter.key === "model" ? "<small>Используется встроенная модель.</small>" : ""}</div>`;
                })
                .join("");
            return `<details class="algorithm"><summary><span class="dot"
                style="background:${colors[index % colors.length]}"></span>${html(descriptor.name)}<small>⌄</small>
            </summary>${fields}<button class="small" data-remove="${index}">Убрать из запуска</button></details>`;
        })
        .join("");
}

export function renderReport(view) {
    const { report, rows, operation } = view;
    const baseline = report.cases[view.baseline];
    const counts = baseline.quality?.counts;
    const fastest = rows
        .filter((row) => mean(row.item) > 0)
        .sort((left, right) => mean(left.item) - mean(right.item))[0];
    $("report-status").textContent = rows.some((row) => caseErrors(row.item).length)
        ? "ЕСТЬ ОШИБКИ"
        : rows.every((row) => row.item.quality?.counts.complete)
          ? "ЗАВЕРШЁН"
          : "НЕПОЛНЫЕ ДАННЫЕ";
    const kpis = [
        ["Попыток размещения", number(counts?.attemptedUnits), "Атомарные заявки; выбранный поток"],
        [
            "Принято baseline",
            number(counts?.admittedUnits),
            counts?.attemptedUnits > 0
                ? `${percent(counts.admittedUnits / counts.attemptedUnits)} от предложенных`
                : "Нет данных качества"
        ],
        [
            "Максимум ускорения",
            fastest && mean(baseline) > 0 ? `${number(mean(baseline) / mean(fastest.item), 2)}×` : "н/д",
            fastest?.label || "Нет замеров"
        ],
        [
            "Событий в сценарии",
            number(baseline.scenario?.normalizedOperations),
            `${report.forks} JVM × ${report.measurementsPerFork} замера · ${report.warmupsPerFork} прогрев`
        ]
    ];
    $("kpis").innerHTML = kpis
        .map(
            ([label, value, note]) => `<div class="kpi"><div class="label">${html(label)}</div>
        <span class="value">${html(value)}</span><small>${html(note)}</small></div>`
        )
        .join("");
    $("samples").textContent = `${report.forks} fork × ${report.measurementsPerFork} замера · мс / окно`;
    $("results").innerHTML =
        `<thead><tr><th>Алгоритм</th><th>Размещено ↑</th><th>Δ к baseline</th><th>Отказы ↓</th>
        <th>Mean ↓</th><th>p50</th><th>p95</th><th>p99</th><th>Ускорение ↑</th><th></th></tr></thead><tbody>` +
        rows
            .map(({ item, index, label, color }) => {
                const quality = item.quality?.counts;
                const difference = quality && counts ? quality.admittedUnits - counts.admittedUnits : null;
                const speed = mean(item) > 0 && mean(baseline) > 0 ? mean(baseline) / mean(item) : null;
                const share =
                    difference !== null && counts.admittedUnits > 0
                        ? `${difference > 0 ? "+" : ""}${number((difference / counts.admittedUnits) * 100, 2)}%`
                        : "н/д";
                return `<tr class="${index === view.baseline ? "baseline" : ""}"><td>
                <span class="dot" style="background:${color};margin-right:8px"></span>${html(label)}
                ${index === view.baseline ? '<span class="baseline-label">baseline</span>' : ""}
                <span class="sub">${html(item.algorithm.id)} · #${index + 1}
                ${caseErrors(item).length ? " · ошибка" : ""}</span></td>
                <td>${number(quality?.admittedUnits)}</td><td class="${difference < 0 ? "bad" : "good"}">
                ${delta(quality?.admittedUnits, counts?.admittedUnits)}<span class="sub">${share}</span></td>
                <td>${number(quality?.declinedUnits)}</td><td>${ms(mean(item))}</td>
                <td>${ms(item.timing?.scheduleP50Nanos?.median)}</td><td>${ms(item.timing?.scheduleP95Nanos?.median)}</td>
                <td>${ms(item.timing?.scheduleP99Nanos?.median)}</td>
                <td class="${speed >= 1 ? "good" : ""}">${speed === null ? "н/д" : number(speed, 2) + "×"}</td>
                <td><button class="small" data-open="${index}">Детали</button>
                <button class="small" data-profile="${index}">Профиль ↗</button></td></tr>`;
            })
            .join("") +
        "</tbody>";
    renderCharts(rows);
    renderResources(rows, operation);
    renderHeadroom(rows, operation);
    $("method-view").innerHTML = `<p>Java ${html(report.java)}, ${html(report.os)} / ${html(report.arch)}.
        Каталог: ${number(report.options?.catalogueLimit)} (0 — полный). Probe cap: ${number(report.options?.probeCap)}.</p>
        <p>Mean — медиана средних по повторам. p50/p95/p99 — медианы перцентилей повторов, без объединения сэмплов.
        При B &gt; 1 время относится ко всему окну планирования. Загрузка, инициализация и пробы качества вне latency.</p>
        <p>В отчёте есть атомарные заявки и суммарные объекты; раздельных pod/shard-счётчиков пока нет.
        Нескольких повторов в одной JVM недостаточно для уверенного статистического ранжирования.</p>
        <p>При завершении потока удалениями финальная занятость равна нулю. Для сравнения ресурсов выберите
        контрольную точку до освобождения. Ошибки и таймауты не считаются отказами размещения.</p>`;
    $("provenance").textContent =
        `${view.source} · ${report.measurement} · scenario ${baseline.scenario?.digest || "н/д"}`;
}

function legend(rows) {
    return `<div class="legend">${rows
        .map(
            (row) => `<span><i class="dot" style="background:${row.color}"></i>
        ${html(row.label)}</span>`
        )
        .join("")}</div>`;
}

function renderCharts(rows) {
    const valid = rows.filter(({ item }) => item.quality?.counts.attemptedUnits > 0 && mean(item) !== null);
    const maxX = Math.max(1, ...valid.map(({ item }) => mean(item) / 1e6)) * 1.2;
    const minY = Math.max(
        0,
        Math.floor(
            Math.min(
                100,
                ...valid.map(
                    ({ item }) =>
                        (item.quality.counts.admittedUnits / item.quality.counts.attemptedUnits) * 100
                )
            ) - 0.35
        )
    );
    let graph = '<svg viewBox="0 0 500 180" aria-label="Скорость и доля размещений">';
    for (let index = 0; index < 4; index++) {
        const y = 22 + index * 37;
        graph += `<line x1="48" x2="480" y1="${y}" y2="${y}" stroke="#34424f" stroke-dasharray="3 5"/>
            <text x="4" y="${y + 4}">${number(100 - (index * (100 - minY)) / 3, 1)}%</text>
            <text x="${48 + index * 144}" y="157">${number((index * maxX) / 3, 1)} мс</text>`;
    }
    for (const { item, label, color } of valid) {
        const x = 48 + (mean(item) / 1e6 / maxX) * 420;
        const y =
            22 +
            ((100 - (item.quality.counts.admittedUnits / item.quality.counts.attemptedUnits) * 100) /
                (100 - minY || 1)) *
                111;
        graph += `<circle cx="${x}" cy="${y}" r="6" fill="${color}"/>
            <text class="label" x="${Math.min(x + 10, 365)}" y="${y - 10}">${html(label)}</text>`;
    }
    $("tradeoff").innerHTML = graph + "</svg>" + legend(rows);
    const checkpoints = rows.flatMap(({ item }) => item.quality?.checkpoints || []);
    const maxOperation = Math.max(1, ...checkpoints.map((value) => value.operation));
    const maxActive = Math.max(1, ...checkpoints.map((value) => value.admittedActiveUnits));
    graph = '<svg viewBox="0 0 500 180" aria-label="Активные заявки в контрольных точках">';
    for (let index = 0; index < 4; index++) {
        const y = 22 + index * 37;
        graph += `<line x1="48" x2="480" y1="${y}" y2="${y}" stroke="#34424f" stroke-dasharray="3 5"/>
            <text x="4" y="${y + 4}">${number(maxActive * (1 - index / 3))}</text>
            <text x="${48 + index * 140}" y="157">${number((index * maxOperation) / 3)}</text>`;
    }
    for (const { item, color } of rows) {
        const points = (item.quality?.checkpoints || []).map(
            (value) =>
                `${48 + (value.operation / maxOperation) * 420},${133 - (value.admittedActiveUnits / maxActive) * 111}`
        );
        graph += `<polyline fill="none" stroke="${color}" stroke-width="2" points="${points.join(" ")}"/>`;
    }
    $("timeline").innerHTML = graph + "</svg>" + legend(rows);
}

function renderResources(rows, operation) {
    const dimensions = [
        ...new Set(rows.flatMap(({ item }) => Object.keys(point(item, operation)?.utilization || {})))
    ];
    $("resources").innerHTML =
        `<thead><tr><th>Ресурс</th>${rows.map((row) => `<th>${html(row.label)}</th>`).join("")}
        </tr></thead><tbody>` +
        dimensions
            .map(
                (dimension) =>
                    `<tr><td class="mono">${html(dimension)}</td>` +
                    rows
                        .map(({ item }) => {
                            const value = point(item, operation)?.utilization?.[dimension];
                            const usage = Number.isFinite(value?.fraction)
                                ? Math.min(100, Math.max(0, value.fraction * 100))
                                : 0;
                            const title =
                                `Занято или недоступно: ${number(value?.usedOrUnavailable, 2)}; ` +
                                `ёмкость: ${number(value?.capacity, 2)}; доступно: ${number(value?.available, 2)}`;
                            return `<td title="${html(title)}"><span class="heat" style="--v:${usage}">${percent(value?.fraction)}</span></td>`;
                        })
                        .join("") +
                    "</tr>"
            )
            .join("") +
        "</tbody>";
}

function renderHeadroom(rows, operation) {
    const profiles = Math.max(0, ...rows.map(({ item }) => point(item, operation)?.holeFilling?.length || 0));
    $("headroom-view").innerHTML =
        `<div class="panel-body"><h2>Дополнительная фиксированная нагрузка</h2>
        <p class="muted">Копия облака после первого заполнения, общий first-fit. Не влияет на основной прогон.</p></div>
        <div class="table-wrap"><table><thead><tr><th>Алгоритм</th><th>Начальный шаг</th><th>Попыток</th><th>Принято</th>
        <th>Первый отказ</th><th>Принято после отказа</th></tr></thead><tbody>` +
        rows
            .map(({ item, label }) => {
                const value = item.quality?.inflation;
                return `<tr><td>${html(label)}</td><td>${number(value?.operation)}</td><td>${number(value?.attemptedUnits)}</td>
                <td>${number(value?.admittedUnits)}</td><td>${number(value?.firstDeclineAttempt)}</td>
                <td>${number(value?.admittedAfterFirstDecline)}</td></tr>`;
            })
            .join("") +
        `</tbody></table></div><details class="panel-body"><summary>Пробы по отдельным типам заявок</summary>
        <p class="muted">Шаг ${number(operation)}. Каждый профиль проверяется независимо; результаты нельзя складывать.
        Подсчёт может упираться в copy cap.</p><div class="table-wrap"><table><thead><tr><th>Тип</th>` +
        rows.map((row) => `<th>${html(row.label)}</th>`).join("") +
        "</tr></thead><tbody>" +
        Array.from(
            { length: profiles },
            (_, index) =>
                `<tr><td>Профиль ${index + 1}</td>` +
                rows
                    .map(({ item }) => {
                        const value = point(item, operation)?.holeFilling?.[index];
                        return `<td>${number(value?.additionalCopies)}<span class="sub">
                    хостов на cap: ${number(value?.hostsAtCopyCap)}</span></td>`;
                    })
                    .join("") +
                "</tr>"
        ).join("") +
        "</tbody></table></div></details>";
}

export function renderDetail(view) {
    const item = view.report.cases[view.activeCase];
    $("detail-title").textContent = algorithmName(item, view.registry) + ` · #${view.activeCase + 1}`;
    $("detail-eyebrow").textContent =
        view.caseDataset(item) +
        " / " +
        (view.detailMode === "profile" ? "диагностика одного алгоритма" : "измеренный прогон");
    $("detail-metrics-tab").classList.toggle("active", view.detailMode === "metrics");
    $("detail-profile-tab").classList.toggle("active", view.detailMode === "profile");
    if (view.detailMode === "profile") {
        renderProfile(view, item);
        return;
    }
    const counts = item.quality?.counts;
    const checkpoint = point(item, view.operation);
    $("detail-body").innerHTML =
        `${caseErrors(item).length ? `<p class="bad">${html(caseErrors(item).join("; "))}</p>` : ""}
        <div class="detail-grid"><section><h3>Качество — отдельное воспроизведение</h3>${pairs({
            Попыток: number(counts?.attemptedUnits),
            Размещено: number(counts?.admittedUnits),
            Отклонено: number(counts?.declinedUnits),
            "Объектов (pod + shard)": number(counts?.admittedResources),
            Удалений: number(counts?.committedRemovals),
            "Удалений без размещения": number(counts?.skippedRemovals),
            "Пик активности": number(counts?.peakActiveUnits),
            "Пик занятых хостов": number(counts?.peakUsedHosts)
        })}</section><section><h3>Контрольная точка ${number(checkpoint?.operation)}</h3>${pairs({
            "Предложено активных заявок": number(checkpoint?.offeredActiveUnits),
            "Размещено активных заявок": number(checkpoint?.admittedActiveUnits),
            "Активных объектов": number(checkpoint?.admittedActiveResources),
            "Занято хостов": number(checkpoint?.usedHosts),
            "Полный replay, медиана (мс)": ms(item.timing?.replayNanos?.median),
            "Удаление p99, медиана (мс)": ms(item.timing?.removalP99Nanos?.median)
        })}</section></div><h3 style="margin-top:22px">Сырые повторы — мс / окно</h3>
        <div class="table-wrap"><table><thead><tr><th>JVM / повтор</th><th>Mean</th><th>p50</th><th>p95</th><th>p99</th>
        <th>Размещено</th></tr></thead><tbody>` +
        samples(item)
            .map(
                (sample) => `<tr>
            <td>Fork ${number(sample.fork)} / ${sample.index}</td>
            <td>${ms(sample.scheduleWindows?.count > 0 ? sample.scheduleWindows.totalNanos / sample.scheduleWindows.count : null)}</td>
            <td>${ms(sample.scheduleWindows?.p50Nanos)}</td><td>${ms(sample.scheduleWindows?.p95Nanos)}</td>
            <td>${ms(sample.scheduleWindows?.p99Nanos)}</td><td>${number(sample.counts?.admittedUnits)}</td></tr>`
            )
            .join("") +
        `</tbody></table></div><details style="margin-top:18px"><summary>Параметры, идентичность и распределения</summary>
        <pre>${html(
            JSON.stringify(
                {
                    algorithm: item.algorithm,
                    input: item.input,
                    scenario: item.scenario,
                    timing: item.timing,
                    checksum: counts?.checksum
                },
                null,
                2
            )
        )}</pre></details>
        <details style="margin-top:18px"><summary>Накопленный объём запросов по ресурсам</summary>
        <p class="muted">Сумма за весь поток, а не текущая утилизация.</p>
        <pre>${html(
            JSON.stringify(
                { requested: item.quality?.requestedLogical, admitted: item.quality?.admittedLogical },
                null,
                2
            )
        )}</pre></details>`;
}

function renderProfile(view, item) {
    const available = view.capabilities.profileAvailable;
    const dataset = view.matchDataset(item);
    const job = view.profileJobs.get(view.activeCase);
    const running = job && ["running", "cancelling"].includes(job.status);
    const complete = job?.status === "complete";
    const format = job?.format || (view.capabilities.flameGraphAvailable ? "html" : "jfr");
    const disabled = !available || !dataset || !item.scenario?.digest || view.running;
    $("detail-body").innerHTML = `<div class="profile-scope">${html(algorithmName(item, view.registry))}
        · ${html(view.caseDataset(item))}<br><span class="mono">${html(JSON.stringify(item.algorithm.parameters))}</span>
        <br><small>Scenario ${html(item.scenario?.digest?.slice(0, 20))}… · ${html(item.scenario?.gpuModel)}
        · B=${html(item.scenario?.bulkSize)}</small></div>
        <div class="control-group" style="margin-top:18px"><label for="profile-event">Событие</label>
        <select id="profile-event" ${running ? "disabled" : ""}>
        ${["cpu", "alloc", "wall"].map((event) => `<option ${event === (job?.event || "cpu") ? "selected" : ""}>${event}</option>`).join("")}
        </select><label for="profile-format">Формат</label><select id="profile-format" ${running ? "disabled" : ""}>
        <option value="html" ${format === "html" ? "selected" : ""}
        ${view.capabilities.flameGraphAvailable ? "" : "disabled"}>HTML flamegraph</option>
        <option value="jfr" ${format === "jfr" ? "selected" : ""}>JFR</option></select>
        <button id="collect-profile" class="primary" ${disabled ? "disabled" : ""}>${running ? "Собираем…" : "Собрать профиль"}</button></div>
        ${!available ? '<p class="note">Укажите ASYNC_PROFILER_HOME при запуске приложения и перезапустите его.</p>' : ""}
        ${!dataset ? '<p class="note">Загрузите исходный датасет. Для профиля должны совпасть SHA256 обоих файлов.</p>' : ""}
        ${!item.scenario?.digest ? '<p class="note">В отчёте нет подготовленного сценария: посмотрите ошибку запуска.</p>' : ""}
        ${job?.error ? `<p class="bad" style="margin-top:14px">${html(job.error)}</p>` : ""}
        <details style="margin-top:14px" ${job ? "" : "hidden"}>
            <summary class="muted">Подробности запуска</summary>
            <pre id="profile-progress">${html(job?.progress || job?.status || "")}</pre>
        </details>
        ${
            complete
                ? `<a class="primary small" id="profile-download"
            href="/api/experiments/artifact?id=${encodeURIComponent(job.id)}" download>↓ Скачать ${html(format.toUpperCase())}</a>
            ${
                format === "html"
                    ? `<a class="small" id="profile-open" href="/api/experiments/artifact?id=${encodeURIComponent(job.id)}"
                target="_blank" rel="noopener noreferrer">Открыть график в отдельной вкладке ↗</a>
                <iframe class="profile-frame" title="Flamegraph выбранного алгоритма"
                sandbox="allow-scripts allow-modals" src="/api/experiments/artifact?id=${encodeURIComponent(job.id)}"></iframe>`
                    : ""
            }`
                : `<div class="empty-profile"><h2>${running ? "Отдельный прогон выбранного алгоритма" : "Профиль ещё не собран"}</h2>
                <p class="muted">Записывается только этот алгоритм с указанными параметрами.
                Состояние симулятора и сохранённые замеры не меняются.</p></div>`
        }
        <p class="muted" style="margin-top:14px">Профилирование — диагностический запуск без прогрева.
        Его время не используется для сравнения latency алгоритмов.</p>`;
}
