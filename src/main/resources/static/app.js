const PROFILE_LABELS = {
    learningGoal: "学习目标",
    learningContent: "学习内容",
    mainDifficulty: "主要困难",
    availableMinutesPerDay: "每天可用时间",
    daysUntilDeadline: "距截止日期",
    preferredLearningStyle: "偏好方式",
    triedMethods: "试过的方法"
};

const PROFILE_UNITS = {
    availableMinutesPerDay: " 分钟",
    daysUntilDeadline: " 天"
};

const EXAMPLE_QUESTIONS = [
    "我背单词很快忘，每天只能学习30分钟",
    "还有一个月考试，书看过了但做题总是错",
    "我学习时容易分心，很难连续专注一小时",
    "专业概念看不懂，看完教材也不知道在讲什么"
];

const OUTCOME_LABELS = {
    helpful: "很有用",
    partial: "有点用",
    not_helpful: "没什么用",
    not_suitable: "不适合我的情况",
    no_time: "还没时间试"
};

// tried 与 outcome 必须自洽，后端和数据库都有同样的约束。
const TRIED_OUTCOMES = ["helpful", "partial", "not_helpful"];
const NOT_TRIED_OUTCOMES = ["not_suitable", "no_time"];

let accessCode = localStorage.getItem("accessCode") || "";
let externalId = localStorage.getItem("externalId");
if (!externalId) {
    externalId = "web-" + crypto.randomUUID().slice(0, 8);
    localStorage.setItem("externalId", externalId);
}
let conversationId = null;
let sending = false;
let historyEvents = [];
let historyViewMode = "method";

const elements = {
    toggleSidebar: document.getElementById("toggleSidebar"),
    toggleProfile: document.getElementById("toggleProfile"),
    sidebar: document.getElementById("sidebar"),
    profilePanel: document.getElementById("profilePanel"),
    newConversation: document.getElementById("newConversation"),
    conversationList: document.getElementById("conversationList"),
    messages: document.getElementById("messages"),
    composer: document.getElementById("composer"),
    input: document.getElementById("input"),
    send: document.getElementById("send"),
    status: document.getElementById("status"),
    profile: document.getElementById("profile"),
    chat: document.getElementById("chat"),
    showHistory: document.getElementById("showHistory"),
    historyView: document.getElementById("historyView"),
    historyList: document.getElementById("historyList"),
    tabByMethod: document.getElementById("tabByMethod"),
    tabByTime: document.getElementById("tabByTime"),
    closeHistory: document.getElementById("closeHistory"),
    historyStatus: document.getElementById("historyStatus")
};

function apiUrl(path) {
    return `/api/v1/users/${encodeURIComponent(externalId)}${path}`;
}

async function http(method, path, body) {
    const headers = {};
    if (body) headers["Content-Type"] = "application/json; charset=utf-8";
    if (accessCode) headers["X-Access-Code"] = accessCode;
    const response = await fetch(apiUrl(path), {
        method,
        headers,
        body: body ? JSON.stringify(body) : undefined
    });
    if (response.status === 401) {
        localStorage.removeItem("accessCode");
        accessCode = "";
        showAccessGate();
        throw new Error("需要有效的访问码");
    }
    if (!response.ok) {
        let detail = "操作没有成功，请稍后再试";
        try {
            const problem = await response.json();
            if (problem.detail) detail = problem.detail;
        } catch (ignored) {
            // 保留默认错误信息
        }
        throw new Error(detail);
    }
    return response.json();
}

/** 历史页打开时对话区是隐藏的，所以状态提示要同时写到两处。 */
function showStatus(text) {
    elements.status.textContent = text || "";
    elements.historyStatus.textContent = text || "";
}

async function loadConversations() {
    try {
        const conversations = await http("GET", "/conversations");
        elements.conversationList.innerHTML = "";
        conversations.forEach(conversation => {
            const item = document.createElement("li");
            const title = document.createElement("span");
            title.textContent = conversation.title;
            const time = document.createElement("span");
            time.className = "time";
            time.textContent = new Date(conversation.updatedAt).toLocaleString("zh-CN");
            item.appendChild(title);
            item.appendChild(time);
            if (conversation.conversationId === conversationId) item.classList.add("active");
            item.addEventListener("click", () => openConversation(conversation.conversationId));
            elements.conversationList.appendChild(item);
        });
    } catch (error) {
        showStatus(error.message);
    }
}

async function openConversation(id) {
    conversationId = id;
    try {
        const messages = await http("GET", `/conversations/${id}/messages`);
        elements.messages.innerHTML = "";
        messages.forEach(renderMessage);
        scrollToBottom();
        loadConversations();
    } catch (error) {
        showStatus(error.message);
    }
}

function startConversation() {
    conversationId = null;
    showWelcome();
    loadConversations();
    elements.input.focus();
}

async function ensureConversation() {
    if (conversationId) return;
    const started = await http("POST", "/conversations");
    conversationId = started.conversationId;
}

async function sendMessage(event) {
    event.preventDefault();
    const content = elements.input.value.trim();
    if (!content || sending) return;
    sending = true;
    elements.send.disabled = true;
    showStatus("");

    try {
        await ensureConversation();
        hideWelcome();
        renderMessage({ role: "user", content });
        elements.input.value = "";
        autoResize();
        const typing = renderTyping();
        scrollToBottom();

        try {
            const turn = await http("POST", `/conversations/${conversationId}/messages`, { content });
            typing.remove();
            if (turn.assistantMessage) {
                renderMessage({ role: "assistant", content: turn.assistantMessage });
            }
            if (turn.recommendation) {
                renderMessage({
                    role: "assistant",
                    content: turn.recommendation.answer,
                    recommendation: turn.recommendation,
                    messageId: turn.assistantMessageId
                });
            }
            loadProfile();
            loadConversations();
        } finally {
            typing.remove();
        }
    } catch (error) {
        showStatus(error.message);
    } finally {
        sending = false;
        elements.send.disabled = false;
        scrollToBottom();
        elements.input.focus();
    }
}

function renderMessage(message) {
    const row = document.createElement("div");
    row.className = `message-row ${message.role}`;

    const avatar = document.createElement("div");
    avatar.className = "avatar";
    avatar.textContent = message.role === "user" ? "我" : "策";
    row.appendChild(avatar);

    const body = document.createElement("div");
    body.className = "message-body";
    const bubble = document.createElement("div");
    bubble.className = "bubble";
    bubble.textContent = message.content;
    body.appendChild(bubble);

    const recommendation = message.recommendation ||
        (message.metadata && message.metadata.messageType === "recommendation"
            ? message.metadata.recommendation : null);
    if (recommendation) {
        bubble.appendChild(renderRecommendation(
            recommendation, message.messageId || null, message.likedStrategies || []));
    }

    row.appendChild(body);
    elements.messages.appendChild(row);
}

function renderTyping() {
    const row = document.createElement("div");
    row.className = "message-row assistant typing";
    row.innerHTML = '<div class="avatar">策</div><div class="message-body">' +
        '<div class="bubble"><span class="dot"></span><span class="dot"></span><span class="dot"></span></div></div>';
    elements.messages.appendChild(row);
    scrollToBottom();
    return row;
}

function renderRecommendation(result, messageId, likedStrategies) {
    const card = document.createElement("div");
    card.className = "recommendation-card";

    const cardTitle = document.createElement("div");
    cardTitle.className = "card-title";
    cardTitle.textContent = result.status === "answer" ? "为你推荐的学习策略" :
        result.status === "clarify" ? "还想再了解一点" : "暂时没有匹配的策略";
    card.appendChild(cardTitle);

    (result.recommendations || []).forEach(recommendation => {
        const block = document.createElement("div");
        block.className = "strategy";

        const name = document.createElement("h3");
        name.textContent = recommendation.strategyName;
        block.appendChild(name);

        const reason = document.createElement("p");
        reason.className = "reason";
        reason.textContent = recommendation.reason;
        block.appendChild(reason);

        if (recommendation.methodSteps && recommendation.methodSteps.length > 0) {
            const steps = document.createElement("ol");
            recommendation.methodSteps.forEach(step => {
                const item = document.createElement("li");
                item.textContent = step;
                steps.appendChild(item);
            });
            block.appendChild(steps);
        }

        (recommendation.caveats || []).forEach(caveat => {
            const note = document.createElement("p");
            note.className = "caveats";
            note.textContent = "注意：" + caveat;
            block.appendChild(note);
        });

        const meta = document.createElement("p");
        meta.className = "meta";
        meta.textContent = "来源 " + (recommendation.sourceIds || []).join("、");
        block.appendChild(meta);

        if (messageId) {
            block.appendChild(renderFeedbackActions(
                messageId, recommendation.strategyId,
                (likedStrategies || []).includes(recommendation.strategyId)));
        }

        card.appendChild(block);
    });

    (result.followUpQuestions || []).forEach(question => {
        const followUp = document.createElement("p");
        followUp.className = "follow-up";
        followUp.textContent = question;
        card.appendChild(followUp);
    });

    return card;
}

/**
 * 卡片上只保留单向点赞和一个跳转入口。
 * 不做「不感兴趣」是因为随手点踩会让好方法仅因为被推给了不合适的人而降权；
 * 真正的效果反馈必须走历史页，那里有上下文，用户是主动填写的。
 */
function renderFeedbackActions(messageId, strategyId, liked) {
    const actions = document.createElement("div");
    actions.className = "feedback-actions";
    actions.appendChild(likeButton(messageId, strategyId, liked));

    const link = document.createElement("button");
    link.type = "button";
    link.className = "trial-link";
    link.textContent = "试过这个方法了？告诉我们效果 →";
    link.addEventListener("click", () => openHistory(strategyId));
    actions.appendChild(link);
    return actions;
}

/**
 * 点赞只能给不能取消，因此已点赞的按钮直接置为 disabled。
 * 这是个有意识的取舍：点赞只展示给投稿者、不参与任何权重计算，
 * 误点的代价极低，不值得为它多做一个 DELETE 接口。
 */
function likeButton(messageId, strategyId, liked) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "like-btn";
    button.textContent = "👍";
    button.title = "觉得这个方法不错，让投稿者知道";
    button.setAttribute("aria-label", "点赞");
    if (liked) {
        button.classList.add("chosen");
        button.disabled = true;
    }
    button.addEventListener("click", async () => {
        button.disabled = true;
        try {
            await http("POST", `/messages/${messageId}/feedback`, { strategyId, action: "liked" });
            button.classList.add("chosen");
        } catch (error) {
            button.disabled = false;
            showStatus(error.message);
        }
    });
    return button;
}

async function loadProfile() {
    try {
        const result = await http("GET", "/profile");
        renderProfile(result.profile || {});
    } catch (error) {
        renderProfile({});
    }
}

function renderProfile(profile) {
    elements.profile.innerHTML = "";
    const fields = Object.keys(PROFILE_LABELS).filter(field => profile[field]);
    if (fields.length === 0) {
        const empty = document.createElement("p");
        empty.className = "profile-empty";
        empty.textContent = "聊几句之后，我会把了解到的学习情况整理在这里。";
        elements.profile.appendChild(empty);
        return;
    }
    fields.forEach(field => {
        const item = document.createElement("div");
        item.className = "profile-item";

        const label = document.createElement("div");
        label.className = "label";
        label.textContent = PROFILE_LABELS[field];
        item.appendChild(label);

        const value = document.createElement("div");
        value.className = "value";
        const raw = profile[field].value;
        const unit = PROFILE_UNITS[field] || "";
        value.textContent = (Array.isArray(raw) ? raw.join("、") : String(raw)) + unit;
        item.appendChild(value);

        if (profile[field].evidence) {
            const quote = document.createElement("div");
            quote.className = "quote";
            quote.textContent = "你说过：“" + profile[field].evidence + "”";
            item.appendChild(quote);
        }

        elements.profile.appendChild(item);
    });
}

/* ---------- 推荐历史与尝试后反馈 ---------- */

/**
 * 打开历史页。focusStrategyId 不为空时自动定位到该方法的卡片，
 * 用于推荐卡片上「试过这个方法了？」的跳转。
 */
async function openHistory(focusStrategyId) {
    elements.chat.classList.add("hidden");
    elements.historyView.classList.remove("hidden");
    showStatus("");
    try {
        historyEvents = await http("GET", "/recommendation-history");
        renderHistory();
        if (focusStrategyId) focusMethod(focusStrategyId);
    } catch (error) {
        showStatus(error.message);
    }
}

function closeHistory() {
    elements.historyView.classList.add("hidden");
    elements.chat.classList.remove("hidden");
}

function switchHistoryView(mode) {
    historyViewMode = mode;
    elements.tabByMethod.classList.toggle("active", mode === "method");
    elements.tabByTime.classList.toggle("active", mode === "time");
    renderHistory();
}

function renderHistory() {
    elements.historyList.innerHTML = "";
    if (historyEvents.length === 0) {
        const empty = document.createElement("p");
        empty.className = "history-empty";
        empty.textContent = "还没有推荐过方法。先去聊聊你在学什么、遇到了什么困难。";
        elements.historyList.appendChild(empty);
        return;
    }
    if (historyViewMode === "method") renderByMethod();
    else renderByTime();
}

/**
 * 按方法聚合。一个人对一个方法只有一个真实体验，不该因为被推荐三次就填三次反馈，
 * 所以反馈绑定 (user, strategy) 而不是 (message, strategy)。
 * historyEvents 已按时间倒序，首次遇到某个方法时的时间就是最近一次推荐时间。
 */
function renderByMethod() {
    const grouped = new Map();
    historyEvents.forEach(event => {
        (event.methods || []).forEach(method => {
            const existing = grouped.get(method.strategyId);
            if (existing) {
                existing.count += 1;
                existing.liked = existing.liked || method.liked;
            } else {
                grouped.set(method.strategyId, {
                    method,
                    count: 1,
                    liked: method.liked,
                    latest: event.recommendedAt,
                    latestMessageId: event.messageId
                });
            }
        });
    });

    grouped.forEach(entry => {
        const card = document.createElement("div");
        card.className = "history-card";
        card.dataset.strategyId = entry.method.strategyId;

        const head = document.createElement("div");
        head.className = "history-card-head";
        const name = document.createElement("h3");
        name.textContent = entry.method.strategyName || entry.method.strategyId;
        head.appendChild(name);
        if (entry.liked) {
            const mark = document.createElement("span");
            mark.className = "liked-mark";
            mark.textContent = "👍 已赞";
            head.appendChild(mark);
        }
        card.appendChild(head);

        if (entry.method.reason) {
            const reason = document.createElement("p");
            reason.className = "reason";
            reason.textContent = entry.method.reason;
            card.appendChild(reason);
        }

        if (entry.method.methodSteps && entry.method.methodSteps.length > 0) {
            const steps = document.createElement("ol");
            entry.method.methodSteps.forEach(step => {
                const item = document.createElement("li");
                item.textContent = step;
                steps.appendChild(item);
            });
            card.appendChild(steps);
        }

        const meta = document.createElement("p");
        meta.className = "meta";
        const parts = ["推荐过 " + entry.count + " 次，最近 " + formatTime(entry.latest)];
        if (entry.method.sourceIds && entry.method.sourceIds.length > 0) {
            parts.push("来源 " + entry.method.sourceIds.join("、"));
        }
        meta.textContent = parts.join("　·　");
        card.appendChild(meta);

        card.appendChild(renderTrialForm(entry.method, entry.latestMessageId));
        elements.historyList.appendChild(card);
    });
}

/** 按时间流水。点任一方法跳到「按方法」视图对应卡片去填反馈。 */
function renderByTime() {
    historyEvents.forEach(event => {
        const group = document.createElement("div");
        group.className = "history-time-group";

        const when = document.createElement("div");
        when.className = "history-time";
        when.textContent = formatTime(event.recommendedAt);
        group.appendChild(when);

        if (event.answer) {
            const answer = document.createElement("p");
            answer.className = "history-answer";
            answer.textContent = event.answer;
            group.appendChild(answer);
        }

        (event.methods || []).forEach(method => {
            const item = document.createElement("button");
            item.type = "button";
            item.className = "history-time-item";
            const marks = [];
            if (method.liked) marks.push("👍");
            if (method.trial) marks.push("已反馈：" + OUTCOME_LABELS[method.trial.outcome]);
            item.textContent = (method.strategyName || method.strategyId)
                + (marks.length > 0 ? "　" + marks.join("　") : "");
            item.addEventListener("click", () => {
                switchHistoryView("method");
                focusMethod(method.strategyId);
            });
            group.appendChild(item);
        });

        elements.historyList.appendChild(group);
    });
}

/**
 * 尝试后反馈表单。两级选择：先问试没试，再按选择给出互斥的效果选项。
 * 没试过时的「不适合我的情况」不参与降权，它是适合人群预测模型最有价值的负样本。
 */
function renderTrialForm(method, sourceMessageId) {
    const form = document.createElement("div");
    form.className = "trial-form";

    const title = document.createElement("div");
    title.className = "trial-title";
    title.textContent = method.trial ? "你的反馈（可随时修改）" : "你试过这个方法吗？";
    form.appendChild(title);

    const state = {
        tried: method.trial ? method.trial.tried : null,
        outcome: method.trial ? method.trial.outcome : null
    };

    const triedRow = document.createElement("div");
    triedRow.className = "trial-row";
    const outcomeRow = document.createElement("div");
    outcomeRow.className = "trial-row hidden";

    function buildOutcomeOptions() {
        outcomeRow.innerHTML = "";
        const options = state.tried ? TRIED_OUTCOMES : NOT_TRIED_OUTCOMES;
        options.forEach(outcome => {
            const button = document.createElement("button");
            button.type = "button";
            button.textContent = OUTCOME_LABELS[outcome];
            if (state.outcome === outcome) button.classList.add("chosen");
            button.addEventListener("click", () => {
                state.outcome = outcome;
                outcomeRow.querySelectorAll("button")
                    .forEach(item => item.classList.remove("chosen"));
                button.classList.add("chosen");
            });
            outcomeRow.appendChild(button);
        });
    }

    [["试过了", true], ["还没试", false]].forEach(pair => {
        const button = document.createElement("button");
        button.type = "button";
        button.textContent = pair[0];
        if (state.tried === pair[1]) button.classList.add("chosen");
        button.addEventListener("click", () => {
            state.tried = pair[1];
            // 切换试没试之后原来的效果选项不再自洽，必须清空重选。
            state.outcome = null;
            triedRow.querySelectorAll("button").forEach(item => item.classList.remove("chosen"));
            button.classList.add("chosen");
            buildOutcomeOptions();
            outcomeRow.classList.remove("hidden");
        });
        triedRow.appendChild(button);
    });
    form.appendChild(triedRow);

    if (state.tried !== null) {
        buildOutcomeOptions();
        outcomeRow.classList.remove("hidden");
    }
    form.appendChild(outcomeRow);

    const note = document.createElement("textarea");
    note.className = "trial-note";
    note.rows = 2;
    note.maxLength = 2000;
    note.placeholder = "想补充点什么吗？比如你具体是怎么用的、卡在哪一步（可选）";
    note.value = method.trial && method.trial.note ? method.trial.note : "";
    form.appendChild(note);

    const savedNote = document.createElement("span");
    savedNote.className = "feedback-note";
    if (method.trial) {
        savedNote.textContent = "已反馈：" + OUTCOME_LABELS[method.trial.outcome]
            + "（" + formatTime(method.trial.updatedAt) + "）";
    }

    const submit = document.createElement("button");
    submit.type = "button";
    submit.className = "trial-submit";
    submit.textContent = "保存反馈";
    submit.addEventListener("click", async () => {
        if (state.tried === null) {
            showStatus("先选一下有没有试过");
            return;
        }
        if (!state.outcome) {
            showStatus("再选一个具体情况");
            return;
        }
        submit.disabled = true;
        try {
            const result = await http("POST",
                `/strategies/${encodeURIComponent(method.strategyId)}/trial-feedback`, {
                    tried: state.tried,
                    outcome: state.outcome,
                    note: note.value.trim() || null,
                    sourceMessageId: sourceMessageId || null
                });
            method.trial = result;
            savedNote.textContent = "已反馈：" + OUTCOME_LABELS[result.outcome]
                + "（" + formatTime(result.updatedAt) + "）";
            showStatus("反馈已记录，谢谢你");
        } catch (error) {
            showStatus(error.message);
        } finally {
            submit.disabled = false;
        }
    });

    const actions = document.createElement("div");
    actions.className = "trial-actions";
    actions.appendChild(submit);
    actions.appendChild(savedNote);
    form.appendChild(actions);

    return form;
}

function focusMethod(strategyId) {
    const card = elements.historyList.querySelector(
        '.history-card[data-strategy-id="' + CSS.escape(strategyId) + '"]');
    if (!card) return;
    card.scrollIntoView({ behavior: "smooth", block: "center" });
    card.classList.add("highlight");
    setTimeout(() => card.classList.remove("highlight"), 1600);
}

function formatTime(value) {
    if (!value) return "";
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString("zh-CN");
}

function showAccessGate() {
    elements.messages.innerHTML = "";
    const gate = document.createElement("div");
    gate.id = "welcome";

    const title = document.createElement("h2");
    title.textContent = "请输入访问码";
    const hint = document.createElement("p");
    hint.textContent = "当前是内测版本，需要测试访问码才能使用。";
    gate.appendChild(title);
    gate.appendChild(hint);

    const form = document.createElement("form");
    form.className = "access-gate";
    const input = document.createElement("input");
    input.placeholder = "访问码";
    input.maxLength = 64;
    input.autocomplete = "off";
    const submit = document.createElement("button");
    submit.type = "submit";
    submit.textContent = "进入";
    const error = document.createElement("p");
    error.className = "access-gate-error";
    form.appendChild(input);
    form.appendChild(submit);
    form.appendChild(error);
    gate.appendChild(form);

    form.addEventListener("submit", async event => {
        event.preventDefault();
        const code = input.value.trim();
        if (!code) return;
        error.textContent = "";
        submit.disabled = true;
        try {
            const response = await fetch("/api/v1/auth/redeem", {
                method: "POST",
                headers: { "Content-Type": "application/json; charset=utf-8" },
                body: JSON.stringify({ code })
            });
            if (!response.ok) throw new Error("访问码无效或已被停用");
            const result = await response.json();
            accessCode = code;
            localStorage.setItem("accessCode", accessCode);
            externalId = result.externalId;
            localStorage.setItem("externalId", externalId);
            showWelcome();
            loadConversations();
            loadProfile();
        } catch (failure) {
            error.textContent = failure.message;
        } finally {
            submit.disabled = false;
        }
    });

    elements.messages.appendChild(gate);
    input.focus();
}

function showWelcome() {
    elements.messages.innerHTML = "";
    const welcome = document.createElement("div");
    welcome.id = "welcome";
    welcome.innerHTML = "<h2>你好，我是学习策略助手</h2>" +
        "<p>告诉我你在学什么、遇到了什么困难，我会从学习方法知识库里为你找合适的策略。</p>";
    const examples = document.createElement("div");
    examples.className = "examples";
    EXAMPLE_QUESTIONS.forEach(question => {
        const chip = document.createElement("button");
        chip.type = "button";
        chip.textContent = question;
        chip.addEventListener("click", () => {
            elements.input.value = question;
            autoResize();
            elements.composer.requestSubmit();
        });
        examples.appendChild(chip);
    });
    welcome.appendChild(examples);
    elements.messages.appendChild(welcome);
}

function hideWelcome() {
    const welcome = document.getElementById("welcome");
    if (welcome) welcome.remove();
}

function scrollToBottom() {
    elements.messages.scrollTop = elements.messages.scrollHeight;
}

function autoResize() {
    elements.input.style.height = "auto";
    elements.input.style.height = Math.min(elements.input.scrollHeight, 120) + "px";
}

elements.toggleSidebar.addEventListener("click", () => elements.sidebar.classList.toggle("hidden"));
elements.toggleProfile.addEventListener("click", () => {
    elements.profilePanel.classList.toggle("hidden");
    loadProfile();
});
elements.newConversation.addEventListener("click", startConversation);
elements.showHistory.addEventListener("click", () => openHistory(null));
elements.closeHistory.addEventListener("click", closeHistory);
elements.tabByMethod.addEventListener("click", () => switchHistoryView("method"));
elements.tabByTime.addEventListener("click", () => switchHistoryView("time"));
elements.composer.addEventListener("submit", sendMessage);
elements.input.addEventListener("input", autoResize);
elements.input.addEventListener("keydown", event => {
    if (event.key === "Enter" && !event.shiftKey) {
        event.preventDefault();
        elements.composer.requestSubmit();
    }
});

if (window.innerWidth < 900) {
    elements.sidebar.classList.add("hidden");
    elements.profilePanel.classList.add("hidden");
}

showWelcome();
loadConversations();
loadProfile();
