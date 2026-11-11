export const $ = id => document.getElementById(id);
export const escapeHtml = value => String(value ?? "").replace(/[&<>"']/g, character => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
})[character]);

const colors = ["#7661dc", "#369e9e", "#d39a4c", "#678fd0", "#b478aa", "#709d63"];
const labels = {
    VCORES: "CPU", MEM_MIB: "RAM", LAN_OUT_MBIT: "Network out", LAN_IN_MBIT: "Network in",
    NVME_GIB: "NVMe", NVME_SPINDLES: "NVMe spindles", SSD_GIB: "SSD", SSD_SPINDLES: "SSD spindles",
    HDD_GIB: "HDD", HDD_SPINDLES: "HDD spindles", NVIDIA_GPUS: "NVIDIA GPU",
    NONPROD_VCORES: "Nonprod CPU", NONPROD_LAN_OUT_MBIT: "Nonprod network out",
    NONPROD_LAN_IN_MBIT: "Nonprod network in", HW_CORES: "Hardware cores",
    HW_LAN_QUEUES: "Hardware LAN queues", INTEL_GPUS: "Intel GPU",
    DEVICE_EXTENTS: "Device extents", DEVICE_SPINDLES: "Device spindles", DEVICE_IOPS: "Device IOPS"
};

export const label = name => labels[name] || name;
export const number = value => value == null ? "—" : Number(value).toLocaleString("ru-RU", {
    maximumFractionDigits: 3
});

function amount(value, name) {
    if (value == null) return "—";
    return number(name === "MEM_MIB" ? value / 1024 : value);
}

function unit(resource) {
    return resource.name === "MEM_MIB" ? "ГиБ" : resource.unit || "";
}

function percentage(used, total) {
    return total > 0 ? Math.max(0, Math.min(100, used / total * 100)) : 0;
}

function color(resource) {
    if (resource.name.includes("GPU")) return "#d3a45d";
    if (resource.name === "MEM_MIB") return "#6598da";
    if (/SSD|HDD|NVME|DEVICE/.test(resource.name)) return "#39a58d";
    if (resource.name.includes("LAN")) return "#7f9ac0";
    return "#7661dc";
}

function bar(resource) {
    return `<div class="bar"><i style="width:${percentage(resource.used, resource.total)}%;
        background:${color(resource)}"></i></div>`;
}

function table(headers, rows) {
    return `<thead><tr>${headers.map(header => `<th>${escapeHtml(header)}</th>`).join("")}</tr></thead>
        <tbody>${rows.map(row => `<tr>${row.map(cell => `<td>${cell}</td>`).join("")}</tr>`).join("")}</tbody>`;
}

export function algorithmName(id, descriptors) {
    return descriptors.find(item => item.id === id)?.name || id;
}

export function renderState(state, view) {
    const lanes = state.lanes;
    const names = lanes.map(lane => algorithmName(lane.algorithm.id, view.algorithms));
    const manual = view.config != null;
    $("source-badge").textContent = manual ? "ручной" : "датасет";
    $("source-note").textContent = `${state.source} · GPU: ${state.gpuMode} · `
        + `${state.rawOperations} исходных / ${state.totalEvents} событий`;
    $("scenario-summary").innerHTML = [
        [lanes[0]?.totalHosts || 0, "хостов"], [state.totalEvents, "событий"],
        [lanes.length, "алгоритмов"], [state.catalogue.length, "типовых запросов"]
    ].map(([value, name]) => `<div><strong>${number(value)}</strong><span>${name}</span></div>`).join("");
    $("scenario-details").innerHTML = [
        ["Источник", state.source], ["Digest", state.digest], ["Размер extent, байт", state.extentBytes],
        ["Перезапусков части unit", state.partialChurn], ["Расширенных перезапусков", state.expandedRestarts]
    ].map(([key, value]) => keyValue(key, value)).join("")
        + `<p class="hint" style="margin-top:9px">Каталог фиксирован: `
        + `${state.catalogue.map(item => escapeHtml(item.name)).join(", ") || "пустой"}.</p>`
        + (manual ? `<button style="margin-top:10px;font-size:10px" data-action="refresh-catalogue">
            Каталог из очереди · начать сначала</button>` : "");
    $("save").disabled = !manual;
    $("add-host").disabled = !manual;
    $("add-request").disabled = !manual;
    $("add-host").title = manual ? "Изменение топологии сбросит проигрывание" : "Доступно в ручном сценарии";
    $("add-request").title = manual ? "Добавить в общий поток" : "Импорт воспроизводится без редактирования";

    renderEvents(state, view);
    $("algorithm-chips").innerHTML = lanes.map((lane, index) => `
        <span class="algorithm-chip"><i style="background:${colors[index]}"></i>${escapeHtml(names[index])}
            <button data-action="algorithm-settings" data-lane="${index}" title="Параметры">⚙</button>
            ${lanes.length > 1 ? `<button data-action="remove-algorithm" data-lane="${index}"
                title="Убрать из сравнения">×</button>` : ""}
        </span>`).join("");
    $("clusters").style.gridTemplateColumns = `repeat(${Math.min(lanes.length, 2)}, minmax(0, 1fr))`;
    $("clusters").innerHTML = lanes.map((lane, index) => cluster(lane, index, names[index], view)).join("");
    applyMapTransform(view);

    const totalHosts = lanes[0]?.totalHosts || 0;
    const shown = lanes[0]?.hosts.length || 0;
    $("hosts-page").textContent = `${shown ? view.hostOffset + 1 : 0}–${view.hostOffset + shown} / ${totalHosts}`;
    $("hosts-prev").disabled = view.hostOffset === 0;
    $("hosts-next").disabled = view.hostOffset + shown >= totalHosts;
    renderTables(state, names);
    const lastEvent = lanes[0]?.lastEvent?.kind ? lanes[0].lastEvent : null;
    const current = view.event || lastEvent
        || state.events.find(event => event.index === state.cursor) || state.events[0];
    renderEvent(current);
    $("progress-text").textContent = `${state.cursor} / ${state.totalEvents} событий`;
    $("progress-fill").style.width = `${percentage(state.cursor, state.totalEvents)}%`;
    $("next").disabled = $("batch").disabled = state.failed || state.cursor >= state.totalEvents;
    $("play").disabled = state.failed || (!view.playing && state.cursor >= state.totalEvents);
    $("play").textContent = view.playing ? "Ⅱ Пауза" : "▶ Авто";
}

function renderEvents(state, view) {
    $("event-count").textContent = `${state.cursor} / ${state.totalEvents}`;
    $("events").innerHTML = state.events.map(event => {
        const create = event.kind === "create";
        return `<button class="event-row ${event.index < state.cursor ? "done" : ""}
                ${event.index === state.cursor ? "current" : ""}"
                data-action="select-event" data-index="${event.index}">
            <span class="event-symbol ${create ? "" : "remove"}">${create ? "+" : "−"}</span>
            <span><strong>${escapeHtml(event.name)}</strong><small>#${event.index + 1} ·
                ${create ? "создание" : "удаление"} · ${event.members.length} ресурсов</small></span>
        </button>`;
    }).join("");
    $("events-page").textContent = state.events.length
        ? `${state.eventOffset + 1}–${state.eventOffset + state.events.length}` : "0";
    $("events-prev").disabled = state.eventOffset === 0;
    $("events-next").disabled = state.eventOffset + state.events.length >= state.totalEvents;
}

function cluster(lane, index, name, view) {
    const counters = lane.counters;
    const stats = [
        [counters.placedPods, "принято pod"], [counters.rejectedUnits, "отказов unit"],
        [counters.activePods, "активных pod"], [counters.activeShards, "активных shard"]
    ];
    const last = lane.lastEvent?.kind ? lane.lastEvent : null;
    const outcomes = { placed: "Размещён", rejected: "Не размещён", removed: "Удалён", skipped: "Удаление: no-op" };
    const outcome = last ? `${outcomes[last.status] || last.status} · ${last.name}` : "Ожидает первого события";
    const descriptor = view.algorithms.find(item => item.id === lane.algorithm.id);
    const parameters = lane.algorithm.parameters;
    const basic = descriptor.parameters.filter(parameter => !parameter.advanced);
    const summary = basic.map(parameter => `${parameter.label}: ${parameters[parameter.key]}`).join(" · ")
        || (descriptor.parameters.length ? "Дополнительные параметры" : "Без параметров");
    const fullSummary = descriptor.parameters.map(parameter =>
        `${parameter.label}: ${parameters[parameter.key]}`).join(" · ");
    return `<article class="cluster">
        <div class="cluster-title"><strong style="color:${colors[index]}">◈ ${escapeHtml(name)}</strong>
            <span class="subtle" title="${escapeHtml(fullSummary)}">${escapeHtml(summary)}</span></div>
        <div class="lane-stats">${stats.map(([count, title]) => `
            <div class="lane-stat"><b>${number(count)}</b><span>${title}</span></div>`).join("")}</div>
        <div class="map" data-map="${index}"><div class="map-stage">
            <svg class="map-links" aria-hidden="true"></svg>
            ${lane.hosts.map((host, position) => node(host, index, position, lane, view)).join("")}
        </div></div>
        <div class="lane-outcome ${last?.status === "rejected" ? "failure" : "success"}">
            ${escapeHtml(outcome)} ${last ? `<span class="subtle">· ${number(last.durationMs)} мс</span>` : ""}
        </div>
    </article>`;
}

function node(host, laneIndex, position, lane, view) {
    const coordinate = view.positions.get(host.id) || { x: 16 + position % 2 * 220, y: 20 + Math.floor(position / 2) * 208 };
    const selected = view.selection?.lane === laneIndex && view.selection?.resource === host.id;
    const lastMembers = lane.lastEvent?.members || [];
    const highlighted = lastMembers.some(member => member.assignment?.host === host.id);
    const visibleResources = host.resources.filter(resource => ["VCORES", "MEM_MIB", "NVIDIA_GPUS", "INTEL_GPUS"]
        .includes(resource.name) && (resource.total > 0 || ["VCORES", "MEM_MIB"].includes(resource.name)));
    return `<article class="node ${selected ? "selected" : ""} ${highlighted ? "highlight" : ""}"
            data-action="select-resource" data-lane="${laneIndex}" data-resource="${host.id}"
            style="left:${coordinate.x}px;top:${coordinate.y}px">
        <div class="node-heading" data-drag-host="${host.id}"><span>▤</span><b>${escapeHtml(host.name)}</b>
            <span class="ready">● ${host.activeResources} active</span></div>
        <div class="node-resources">${visibleResources.map(resource => `
            <div><div class="resource-label"><span>${escapeHtml(label(resource.name))}</span>
                <b>${amount(resource.used, resource.name)} / ${amount(resource.total, resource.name)}</b>
            </div>${bar(resource)}</div>`).join("")}</div>
        <div class="node-members">${host.members.length ? host.members.map(member => `
            <button class="member-chip ${member.kind === "SHARD" ? "shard" : ""}"
                data-action="select-resource" data-lane="${laneIndex}" data-resource="${member.id}"
                title="${escapeHtml(member.name)}">${escapeHtml(member.name)}</button>`).join("")
            : `<span class="empty-node">Нет размещений · свободный узел</span>`}
            ${host.activeResources > host.members.length
                ? `<span class="empty-node">+${host.activeResources - host.members.length} · в инспекторе</span>` : ""}
        </div>
    </article>`;
}

function renderTables(state, names) {
    const lanes = state.lanes;
    const quality = [
        ["Попыток создания unit", "attemptedUnits"], ["Размещено unit", "placedUnits"],
        ["Отклонено unit", "rejectedUnits"], ["Активных unit", "activeUnits"],
        ["Попыток pod", "attemptedPods"], ["Размещено pod", "placedPods"], ["Активных pod", "activePods"],
        ["Попыток shard", "attemptedShards"], ["Размещено shard", "placedShards"],
        ["Активных shard", "activeShards"], ["Попыток GPU-pod", "attemptedGpuPods"],
        ["Размещено GPU-pod", "placedGpuPods"], ["Активных GPU-pod", "activeGpuPods"],
        ["Успешных удалений unit", "removedUnits"], ["Удалено pod", "removedPods"],
        ["Удалено shard", "removedShards"], ["Удалений no-op", "skippedRemovals"]
    ];
    quality.push(["Занято хостов", "usedHosts"]);
    const baseline = lanes.find(lane => lane.algorithm.id === "fitcount");
    const rows = quality.map(([title, key]) => [escapeHtml(title), ...lanes.map(lane => number(lane.counters[key]))]);
    if (baseline) rows.splice(7, 0, ["Δ размещённых pod к FitCount", ...lanes.map(lane => {
        const delta = lane.counters.placedPods - baseline.counters.placedPods;
        const percent = baseline.counters.placedPods ? delta / baseline.counters.placedPods * 100 : null;
        return `${delta > 0 ? "+" : ""}${number(delta)} <small>(${number(percent)}%)</small>`;
    })]);
    for (const [title, suffix] of [["pod", "Pods"], ["shard", "Shards"], ["GPU-pod", "GpuPods"]]) {
        rows.push([`Отклонено ${title}`, ...lanes.map(lane =>
            number(lane.counters[`attempted${suffix}`] - lane.counters[`placed${suffix}`]))]);
    }
    $("quality-table").innerHTML = table(["Показатель", ...names], rows);
    $("resource-table").innerHTML = table(["Ресурс", ...names], (lanes[0]?.resources || []).map(resource => [
        `${escapeHtml(label(resource.name))} <small>${escapeHtml(unit(resource))}</small>`,
        ...lanes.map(lane => {
            const value = lane.resources.find(item => item.id === resource.id);
            if (!value) return "—";
            if (value.capacityKnown === false) return `<span class="subtle">Без лимита в модели</span>`;
            return `<div class="resource-cell"><span>${amount(value.used, value.name)} /
                ${amount(value.total, value.name)}</span>${bar(value)}
                <small>${value.total ? number(percentage(value.used, value.total)) + "%" : "—"}</small></div>`;
        })
    ]));
    const latencyRows = [];
    for (const [type, title] of [["create", "Создание"], ["remove", "Удаление"]]) {
        for (const [key, metric] of [["count", "вызовов"], ["meanMs", "mean"], ["p50Ms", "p50"],
            ["p95Ms", "p95"], ["p99Ms", "p99"], ["lastMs", "последнее"]]) {
            latencyRows.push([`${title} · ${metric}`, ...lanes.map(lane => {
                const timing = lane.latency[type];
                return number(key !== "count" && timing.count === 0 ? null : timing[key]);
            })]);
        }
    }
    latencyRows.push(["Вызовов planner", ...lanes.map(lane => lane.latency.plannerCalls < 0
        ? "—" : number(lane.latency.plannerCalls))]);
    $("latency-table").innerHTML = table(["Метрика", ...names], latencyRows);
}

function keyValue(key, value) {
    return `<div class="kv"><span>${escapeHtml(key)}</span><b>${escapeHtml(value ?? "—")}</b></div>`;
}

function demandCells(resources) {
    return `<div class="request-grid">${resources.filter(resource => resource.amount !== 0).map(resource => `
        <div class="request-cell">${escapeHtml(label(resource.name))}
            <b>${amount(resource.amount, resource.name)} ${escapeHtml(unit(resource))}</b></div>`).join("")}</div>`;
}

export function renderEvent(event) {
    $("current-title").textContent = "Событие";
    $("current-index").textContent = event ? `#${event.index + 1}` : "—";
    if (!event) {
        $("current-event").innerHTML = `<p class="hint">Создайте запрос или откройте сценарий.</p>`;
        return;
    }
    $("current-event").innerHTML = `<span class="badge">${event.kind === "create" ? "+ CREATE" : "− DELETE"}</span>
        <h3 class="object-title">${escapeHtml(event.name)}</h3>
        <p class="hint">Атомарный запрос · ${event.members.length} ресурсов. Удаляется целиком.</p>
        ${event.members.map(member => `<div class="object-member">
            <strong>${escapeHtml(member.name)}</strong> <span class="subtle">${escapeHtml(member.kind)}</span>
            ${demandCells(member.resources || [])}
        </div>`).join("")}`;
}

export function renderInspector(detail, view) {
    $("inspector-kind").textContent = detail.kind;
    const isHost = detail.kind === "HOST";
    const isInventory = isHost || detail.kind === "NUMA" || detail.kind === "DISK";
    let content = `<h3 class="object-title">${escapeHtml(detail.name)}</h3>
        <p class="hint">${escapeHtml(view.laneName)} · ID ${detail.id}</p>`;
    if (isInventory) {
        content += detail.resources.filter(resource => resource.total || resource.used || resource.capacityKnown === false)
            .map(resource => keyValue(label(resource.name), resource.capacityKnown === false
                ? "Без лимита в модели" : `${amount(resource.used, resource.name)} / `
                    + `${amount(resource.total, resource.name)} ${unit(resource)}`)).join("");
        if (detail.gpus?.length) {
            content += "<h3>GPU-устройства · synthetic</h3>";
            content += detail.gpus.map(gpu => `<div class="gpu-device"><div class="gpu-label">
                <span>${gpu.type === "INTEL_GPUS" ? "Intel" : "NVIDIA"} #${gpu.id}</span>
                <b>${gpu.totalShares - gpu.freeShares}/${gpu.totalShares} долей</b></div>
                <div class="shares">${Array.from({ length: gpu.totalShares }, (_, index) =>
                    `<i class="${index < gpu.totalShares - gpu.freeShares ? "used" : ""}"></i>`).join("")}</div>
            </div>`).join("");
        }
        const members = detail.members || [];
        if (isHost) content += `<h3>Размещённые ресурсы · ${members.length}</h3>`;
        content += members.map(member => `<div class="member-line">
            <button class="link" data-action="select-resource" data-lane="${view.lane}"
                data-resource="${member.id}">${escapeHtml(member.name)}</button>
            <span class="subtle">${escapeHtml(member.kind)}</span></div>`).join("")
            || (isHost ? `<p class="hint">Нет размещений.</p>` : "");
        if (detail.parent) content += `<div class="member-line"><span>Родительский хост</span>
            <button class="link" data-action="select-resource" data-lane="${view.lane}"
                data-resource="${detail.parent}">#${detail.parent}</button></div>`;
        for (const [key, title] of [["numa", "NUMA"], ["disks", "Диски"]]) {
            content += `<details><summary>${title} · ${detail[key]?.length || 0}</summary>`;
            content += (detail[key] || []).map(item => `<div class="object-member">
                <button class="link" data-action="select-resource" data-lane="${view.lane}"
                    data-resource="${item.id}">${escapeHtml(item.name)}</button>
                ${(item.resources || []).filter(resource => resource.total > 0).map(resource =>
                    keyValue(label(resource.name), `${amount(resource.used, resource.name)} / `
                        + `${amount(resource.total, resource.name)} ${unit(resource)}`)).join("")}</div>`).join("");
            content += "</details>";
        }
    } else {
        content += demandCells(detail.resources || []);
        if (detail.assignment) {
            content += `<h3>Размещение</h3>`;
            for (const [key, title] of [["host", "Хост"], ["numa", "NUMA"]]) {
                const target = detail.assignment[key];
                if (target) content += `<div class="member-line"><span>${title}</span>
                    <button class="link" data-action="select-resource" data-lane="${view.lane}"
                        data-resource="${target}">#${target}</button></div>`;
            }
            content += keyValue("Дисковые устройства", detail.assignment.devices?.join(", ") || "—");
            content += keyValue("GPU ID", detail.assignment.gpus?.join(", ") || "—");
            if (view.config && detail.request != null) content += `<button style="margin-top:12px"
                data-action="delete-unit" data-unit="${detail.request}">Удалить атомарный запрос</button>`;
        } else if (["POD", "SHARD"].includes(detail.kind)) {
            content += `<p class="hint" style="margin-top:12px">Сейчас не размещён.</p>`;
        }
        content += `<details open><summary>Ограничения и связи</summary>`;
        content += Object.entries(detail.rules || {}).map(([key, value]) =>
            keyValue(key, typeof value === "object" ? JSON.stringify(value) : value)).join("");
        content += (detail.links || []).map(link => {
            const relation = ["COLOCATE", "READY_GROUP", "SHARED_GROUP"][link.kind] || link.kind;
            const target = link.kind === 0 ? `<button class="link" data-action="select-resource"
                data-lane="${view.lane}" data-resource="${link.target}">Ресурс #${link.target}</button>`
                : `<span>Группа #${link.target}</span>`;
            return `<div class="member-line"><span>${escapeHtml(relation)} · domain ${link.domain}</span>
                ${target}</div>`;
        }).join("");
        content += "</details>";
        const peers = (detail.unitMembers || []).filter(member => member.id !== detail.id);
        if (peers.length) content += `<h3>В том же атомарном запросе</h3>` + peers.map(member => `
            <div class="member-line"><button class="link" data-action="select-resource" data-lane="${view.lane}"
                data-resource="${member.id}">${escapeHtml(member.name)}</button>
                <span class="subtle">${escapeHtml(member.kind)}</span></div>`).join("");
    }
    content += `<details><summary>Все поля объекта</summary><pre class="raw-json">`
        + `${escapeHtml(JSON.stringify(detail, null, 2))}</pre></details>`;
    $("inspector").innerHTML = content;
}

export function renderProbes(result, descriptors) {
    const profiles = result.lanes[0]?.fitCounts || [];
    $("probes").innerHTML = `<p class="hint">Состояние после ${result.cursor} событий. `
        + `Общий cap: ${number(result.lanes[0]?.perHostCap)} копий на хост. `
        + `Результат остаётся привязан к этому шагу; это диагностическая оценка, не доказательство оптимума.</p>`
        + `<div class="table-scroll"><table>` + table(["Профиль", ...result.lanes.map(lane => algorithmName(
            typeof lane.algorithm === "string" ? lane.algorithm : lane.algorithm.id, descriptors))],
        profiles.map((profile, index) => [escapeHtml(profile.name), ...result.lanes.map(lane =>
            number(lane.fitCounts[index]?.count))])) + `</table></div>`;
}

export function applyMapTransform(view) {
    document.querySelectorAll(".map-stage").forEach(stage => {
        stage.style.transform = `translate(${view.pan.x}px, ${view.pan.y}px) scale(${view.zoom})`;
    });
    $("zoom-level").textContent = `${Math.round(view.zoom * 100)}%`;
}

export function drawLinks(detail, selection) {
    document.querySelectorAll(".map-links").forEach(svg => svg.replaceChildren());
    if (!detail || !selection) return;
    const map = document.querySelector(`[data-map="${selection.lane}"]`);
    if (!map) return;
    const stage = map.querySelector(".map-stage");
    const source = stage.querySelector(`[data-resource="${detail.id}"]`);
    if (!source) return;
    const origin = source.closest(".node");
    const links = [...(detail.links || []), ...(detail.unitMembers || [])
        .filter(member => member.id !== detail.id).map(member => ({ kind: 0, target: member.id }))];
    for (const link of links) {
        if (link.kind !== 0) continue;
        const target = stage.querySelector(`[data-resource="${link.target}"]`)?.closest(".node");
        if (!target) continue;
        const x = origin.offsetLeft + origin.offsetWidth / 2;
        const y = origin.offsetTop + origin.offsetHeight;
        const tx = target.offsetLeft + target.offsetWidth / 2;
        const ty = target.offsetTop + target.offsetHeight;
        const path = document.createElementNS("http://www.w3.org/2000/svg", "path");
        const control = Math.max(y, ty) + 30;
        path.setAttribute("d", `M ${x} ${y} C ${x} ${control}, ${tx} ${control}, ${tx} ${ty}`);
        stage.querySelector(".map-links").append(path);
    }
}
