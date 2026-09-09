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

let accessCode = localStorage.getItem("accessCode") || "";
let externalId = localStorage.getItem("externalId");
if (!externalId) {
    externalId = "web-" + crypto.randomUUID().slice(0, 8);
    localStorage.setItem("externalId", externalId);
}
let conversationId = null;
let sending = false;

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
    profile: document.getElementById("profile")
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

function showStatus(text) {
    elements.status.textContent = text || "";
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
        bubble.appendChild(renderRecommendation(recommendation, message.messageId || null));
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

function renderRecommendation(result, messageId) {
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
            block.appendChild(renderFeedbackActions(messageId, recommendation.strategyId));
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

function renderFeedbackActions(messageId, strategyId) {
    const actions = document.createElement("div");
    actions.className = "feedback-actions";
    actions.appendChild(feedbackButton("采纳", messageId, strategyId, "adopted"));
    actions.appendChild(feedbackButton("不感兴趣", messageId, strategyId, "dismissed"));
    return actions;
}

function feedbackButton(label, messageId, strategyId, action) {
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = label;
    button.addEventListener("click", async () => {
        const container = button.parentElement;
        container.querySelectorAll("button").forEach(item => item.disabled = true);
        try {
            await http("POST", `/messages/${messageId}/feedback`, { strategyId, action });
            button.classList.add("chosen");
            const note = document.createElement("span");
            note.className = "feedback-note";
            note.textContent = "已记录";
            container.appendChild(note);
        } catch (error) {
            container.querySelectorAll("button").forEach(item => item.disabled = false);
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
