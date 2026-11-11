const ui = {
    form: document.querySelector("#draw-form"),
    activity: document.querySelector("#activity"),
    activityNote: document.querySelector("#activity-note"),
    activityPeriod: document.querySelector("#activity-period"),
    activityEnd: document.querySelector("#activity-end"),
    refresh: document.querySelector("#refresh-activities"),
    user: document.querySelector("#user-id"),
    draw: document.querySelector("#draw-button"),
    drawLabel: document.querySelector("#draw-button-label"),
    wheel: document.querySelector("#wheel-face"),
    result: document.querySelector("#result"),
    resultTitle: document.querySelector("#result-title"),
    resultDetail: document.querySelector("#result-detail"),
    resultReference: document.querySelector("#result-reference"),
};

const sessionKey = "lottery.draw.session";
const session = JSON.parse(sessionStorage.getItem(sessionKey)) ?? {
    activityId: "",
    pending: {},
};
const dateFormat = new Intl.DateTimeFormat("zh-CN", {dateStyle: "medium", timeStyle: "short"});
let activities = [];
let activityState = "loading";
let drawing = false;
let wheelAngle = 0;

function newRequestId() {
    return Array.from(crypto.getRandomValues(new Uint8Array(16)), byte => byte.toString(16).padStart(2, "0")).join("");
}

function currentUser() { return ui.user.value; }
function contextKey() { return `${ui.activity.value}/${currentUser()}`; }
function pendingDraw() { return session.pending[contextKey()]; }
function selectedActivity() { return activities.find(item => String(item.activityId) === ui.activity.value); }

function rememberSelection() {
    if (ui.activity.value) session.activityId = ui.activity.value;
    sessionStorage.setItem(sessionKey, JSON.stringify(session));
}

function showResult(state, title, detail, reference = "") {
    ui.result.dataset.state = state;
    ui.resultTitle.textContent = title;
    ui.resultDetail.textContent = detail;
    ui.resultReference.textContent = reference ? `请求号：${reference}` : "";
    ui.resultReference.hidden = !reference;
}

function showBlockedDraw(draw) {
    showResult("error", "这轮抽奖需要核查", "请将请求号提供给活动管理员，核查后再恢复抽奖。", draw.requestId);
}

function showContextResult() {
    const pending = pendingDraw();
    if (pending?.blocked) {
        showBlockedDraw(pending);
    } else if (pending) {
        showResult("pending", "有一轮抽奖待确认", "点击「恢复抽奖」继续上一次抽奖。");
    } else {
        showResult("idle", "等待揭晓", "抽奖完成后，结果会显示在这里。");
    }
}

function renderControls() {
    const pending = pendingDraw();
    const loading = activityState === "loading";
    ui.refresh.disabled = drawing || loading;
    ui.activity.disabled = drawing || loading || !ui.activity.value;
    ui.draw.disabled = drawing || loading || !currentUser() || (!pending && !selectedActivity());
    ui.drawLabel.textContent = drawing ? "正在抽奖…" : pending ? "恢复抽奖" : "开始抽奖";
}

function renderActivityDetails() {
    const activity = selectedActivity();
    const pending = pendingDraw();
    ui.activityPeriod.hidden = !activity?.endDateTime;
    if (activity?.endDateTime) {
        const end = new Date(activity.endDateTime);
        ui.activityEnd.dateTime = end.toISOString();
        ui.activityEnd.textContent = dateFormat.format(end);
    }
    if (activity) {
        ui.activityNote.textContent = activity.activityDesc ?? "";
    } else if (pending) {
        ui.activityNote.textContent = "原活动当前不可选，仍可恢复这一轮抽奖。";
    } else if (activityState === "error") {
        ui.activityNote.textContent = "活动列表加载失败，请点击「刷新活动」重试。";
    } else if (activityState === "ready") {
        ui.activityNote.textContent = "当前没有进行中的活动，可稍后刷新。";
    } else {
        ui.activityNote.textContent = "";
    }
}

function renderActivities(preferredId = ui.activity.value) {
    const options = new Map(activities.map(item => [String(item.activityId), item.activityName]));
    // 后端先按请求号恢复，再校验活动状态；活动到期也要保留这个用户的恢复入口。
    for (const draw of Object.values(session.pending)) {
        const id = String(draw.activityId);
        if (draw.userId === currentUser() && !options.has(id)) options.set(id, `${draw.activityName}（待确认）`);
    }
    const placeholder = {loading: "正在加载活动…", ready: "暂无活动", error: "活动加载失败"};
    ui.activity.replaceChildren(...(options.size
        ? Array.from(options, ([id, name]) => new Option(name, id))
        : [new Option(placeholder[activityState], "")]));
    if (options.has(preferredId)) ui.activity.value = preferredId;
    renderActivityDetails();
    renderControls();
}

async function fetchJson(path, options) {
    const timeout = new AbortController();
    const timer = setTimeout(() => timeout.abort(), 12000);
    try {
        const response = await fetch(`./api/v1/${path}`, {...options, cache: "no-store", signal: timeout.signal});
        if (!response.ok && !(path === "draw" && [429, 503].includes(response.status))) {
            throw new Error(`${path}: HTTP ${response.status}`);
        }
        return await response.json();
    } finally {
        clearTimeout(timer);
    }
}

async function loadActivities() {
    const previousContext = contextKey();
    // 身份由服务端提供，刷新后仍按原账户恢复待确认请求。
    const identity = fetchJson("draw/identity").then(result => {
        ui.user.value = result.userId;
    }).catch(error => {
        console.error("加载抽奖身份失败", error);
        ui.user.value = "";
    });
    const preferredId = ui.activity.value || session.activityId;
    activityState = "loading";
    renderControls();
    try {
        const items = await fetchJson("activities");
        // Java Long 超出 JS 整数精度时不能把舍入后的 ID 发给抽奖接口。
        if (items.some(item => !Number.isSafeInteger(item.activityId))) throw new Error("活动 ID 超出浏览器整数精度");
        activities = items;
        activityState = "ready";
    } catch (error) {
        console.error("加载活动列表失败", error);
        activities = [];
        activityState = "error";
    }
    await identity;
    renderActivities(preferredId);
    if (!currentUser()) {
        showResult("error", "当前未登录", "请登录后再参加抽奖。");
    } else if (contextKey() !== previousContext) {
        showContextResult();
    }
}

function spinWheel() {
    if (matchMedia("(prefers-reduced-motion: reduce)").matches) return Promise.resolve();
    // 转两圈后停在下一个刻度；中奖与否只读取服务端响应。
    const animation = ui.wheel.animate([
        {transform: `rotate(${wheelAngle}deg)`},
        {transform: `rotate(${wheelAngle + 765}deg)`},
    ], {duration: 1150, easing: "cubic-bezier(.14, .65, .19, 1)"});
    wheelAngle = (wheelAngle + 45) % 360;
    ui.wheel.style.transform = `rotate(${wheelAngle}deg)`;
    return animation.finished;
}

function revealResult(result, draw, key) {
    if (result.requestId !== draw.requestId || typeof result.code !== "string") {
        throw new Error("响应缺少业务码或请求号不匹配");
    }
    if (result.code !== "D008") delete draw.blocked;
    // 中奖和未中奖都结束本轮。明确拒绝也不需要恢复；其他异常继续使用旧请求号。
    switch (result.code) {
        case "0000":
            if (!result.win || !result.award?.awardName) throw new Error("中奖响应缺少奖品信息");
            delete session.pending[key];
            showResult("win", `抽中了！${result.award.awardName}`, "中奖订单已生成，奖品将另行处理。");
            break;
        case "D001":
            delete session.pending[key];
            showResult("miss", "这次没有中奖", "本轮抽奖已结束。");
            break;
        case "0004":
            delete session.pending[key];
            showResult("error", "抽奖次数已用完", "这个用户在当前活动已没有抽奖机会。");
            break;
        case "0002":
            delete session.pending[key];
            showResult("error", "无法参加抽奖", "请检查用户 ID 和所选活动。");
            break;
        case "L001":
        case "L002":
        case "L003":
        case "L004":
            showResult("pending", "暂未受理抽奖", result.info);
            break;
        case "D006":
            showResult("pending", "这轮抽奖还在处理", "稍后点击「恢复抽奖」，继续查看本轮结果。");
            break;
        case "D007":
            // 只采用后端确认的原请求号，当前请求号未取得原参与记录。
            if (!result.pendingRequestId) throw new Error("后端未返回待恢复的请求号");
            draw.requestId = result.pendingRequestId;
            showContextResult();
            break;
        case "D008":
            draw.blocked = true;
            showBlockedDraw(draw);
            break;
        default:
            console.error("抽奖结果待确认", draw.requestId, result.code, result.info);
            showResult("error", "暂未确认本轮结果", "请稍后点击「恢复抽奖」，继续同一轮抽奖。");
    }
}

async function submitDraw(event) {
    event.preventDefault();
    if (ui.draw.disabled) return;
    const userId = currentUser();

    const key = contextKey();
    const activity = selectedActivity();
    const draw = session.pending[key] ?? {
        activityId: activity.activityId,
        activityName: activity.activityName,
        userId,
        requestId: newRequestId(),
    };
    // 先保存本轮身份与请求号，再发请求；刷新页面或丢失响应都能恢复同一轮。
    session.pending[key] = draw;
    rememberSelection();
    drawing = true;
    renderControls();
    showResult("busy", "正在揭晓", "等一等，马上揭晓这一轮的结果。");
    const animation = spinWheel();

    try {
        const {activityId, requestId} = draw;
        const result = await fetchJson("draw", {
            method: "POST",
            headers: {"Content-Type": "application/json"},
            body: JSON.stringify({activityId, userId, requestId}),
        });
        await animation;
        revealResult(result, draw, key);
    } catch (error) {
        await animation;
        console.error("抽奖请求失败", draw.requestId, error);
        showResult("error", "暂未收到抽奖结果", "点击「恢复抽奖」，继续同一轮抽奖。");
    } finally {
        // 保留待恢复的记录，或持久化已经结束的本轮状态。
        rememberSelection();
        drawing = false;
        renderActivities();
    }
}

ui.form.addEventListener("submit", submitDraw);
ui.refresh.addEventListener("click", loadActivities);
ui.activity.addEventListener("change", () => {
    rememberSelection();
    renderActivityDetails();
    renderControls();
    showContextResult();
});
renderActivities(session.activityId);
showContextResult();
loadActivities();
