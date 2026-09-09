const PROFILE_LABELS = {
    learningGoal: "学习目标",
    learningContent: "学习内容",
    mainDifficulty: "主要困难",
    availableMinutesPerDay: "每天可用分钟",
    daysUntilDeadline: "距截止天数",
    preferredLearningStyle: "偏好学习方式",
    triedMethods: "已尝试方法"
};

let externalId = localStorage.getItem("externalId") || "";
let conversationId = null;

const elements = {
    externalId: document.getElementById("externalId"),
    loadUser: document.getElementById("loadUser"),
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
    const response = await fetch(apiUrl(path), {
        method,
        headers: body ? { "Content-Type": "application/json; charset=utf-8" } : undefined,
        body: body ? JSON.stringify(body) : undefined
    });
    if (!response.ok) {
        let detail = `请求失败（HTTP ${response.status}）`;
        try {
            const problem = await response.json();
            if (problem.detail) detail = `${problem.title || "请求失败"}：${problem.detail}`;
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

function setReady(ready) {
    elements.newConversation.disabled = !ready;
    elements.input.disabled = !ready;
    elements.send.disabled = !ready;
}

async function loadConversations() {
    try {
        const conversations = await http("GET", "/conversations");
        elements.conversationList.innerHTML = "";
        conversations.forEach(conversation => {
            const item = document.createElement("li");
            item.textContent = conversation.conversationId.slice(0, 8) + " · " +
                new Date(conversation.updatedAt).toLocaleString("zh-CN");
            item.dataset.conversationId = conversation.conversationId;
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
    elements.messages.innerHTML = "";
    try {
        const messages = await http("GET", `/conversations/${id}/messages`);
        messages.forEach(renderMessage);
        scrollToBottom();
        loadConversations();
    } catch (error) {
        showStatus(error.message);
    }
}

async function startConversation() {
    try {
        const started = await http("POST", "/conversations");
        conversationId = started.conversationId;
        elements.messages.innerHTML = "";
        showStatus("");
        loadConversations();
    } catch (error) {
        showStatus(error.message);
    }
}

async function sendMessage(event) {
    event.preventDefault();
    const content = elements.input.value.trim();
    if (!content) return;
    if (!conversationId) await startConversation();
    if (!conversationId) return;

    renderMessage({ role: "user", content });
    elements.input.value = "";
    scrollToBottom();
    showStatus("");

    try {
        const turn = await http("POST", `/conversations/${conversationId}/messages`, { content });
        if (turn.assistantMessage) {
            renderMessage({ role: "assistant", content: turn.assistantMessage });
        }
        if (turn.recommendation) {
            renderMessage({ role: "assistant", content: turn.recommendation.answer, recommendation: turn.recommendation });
        }
        scrollToBottom();
        loadProfile();
    } catch (error) {
        showStatus(error.message);
    }
}

function renderMessage(message) {
    const bubble = document.createElement("div");
    bubble.className = `message ${message.role}`;
    bubble.textContent = message.content;
    const recommendation = message.recommendation ||
        (message.metadata && message.metadata.messageType === "recommendation" ? message.metadata.recommendation : null);
    if (recommendation) {
        bubble.appendChild(renderRecommendation(recommendation));
    }
    elements.messages.appendChild(bubble);
}

function renderRecommendation(result) {
    const card = document.createElement("div");
    card.className = "recommendation-card";

    const title = document.createElement("h3");
    title.textContent = result.status === "answer" ? "推荐学习策略" :
        result.status === "clarify" ? "还需要了解一些信息" : "暂时没有匹配的策略";
    card.appendChild(title);

    (result.recommendations || []).forEach(recommendation => {
        const name = document.createElement("h3");
        name.textContent = recommendation.strategyName;
        card.appendChild(name);

        const reason = document.createElement("p");
        reason.className = "reason";
        reason.textContent = recommendation.reason;
        card.appendChild(reason);

        if (recommendation.methodSteps && recommendation.methodSteps.length > 0) {
            const steps = document.createElement("ol");
            recommendation.methodSteps.forEach(step => {
                const item = document.createElement("li");
                item.textContent = step;
                steps.appendChild(item);
            });
            card.appendChild(steps);
        }

        (recommendation.caveats || []).forEach(caveat => {
            const note = document.createElement("p");
            note.className = "caveats";
            note.textContent = "注意：" + caveat;
            card.appendChild(note);
        });

        const meta = document.createElement("p");
        meta.className = "meta";
        meta.textContent = "来源：" + (recommendation.sourceIds || []).join("、") +
            "；引用：" + (recommendation.citations || []).join("、");
        card.appendChild(meta);
    });

    (result.followUpQuestions || []).forEach(question => {
        const followUp = document.createElement("p");
        followUp.textContent = "追问：" + question;
        card.appendChild(followUp);
    });

    return card;
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
        const empty = document.createElement("dt");
        empty.className = "empty";
        empty.textContent = "开始对话后这里会展示画像信息";
        elements.profile.appendChild(empty);
        return;
    }
    fields.forEach(field => {
        const label = document.createElement("dt");
        label.textContent = PROFILE_LABELS[field];
        elements.profile.appendChild(label);

        const value = document.createElement("dd");
        const raw = profile[field].value;
        value.textContent = Array.isArray(raw) ? raw.join("、") : String(raw);
        elements.profile.appendChild(value);

        if (profile[field].evidence) {
            const evidence = document.createElement("dd");
            evidence.className = "evidence";
            evidence.textContent = "依据：" + profile[field].evidence;
            elements.profile.appendChild(evidence);
        }
    });
}

function scrollToBottom() {
    elements.messages.scrollTop = elements.messages.scrollHeight;
}

elements.loadUser.addEventListener("click", () => {
    const id = elements.externalId.value.trim();
    if (!id) {
        showStatus("请先输入用户标识");
        return;
    }
    externalId = id;
    localStorage.setItem("externalId", externalId);
    conversationId = null;
    elements.messages.innerHTML = "";
    setReady(true);
    showStatus("");
    loadConversations();
    loadProfile();
});

elements.newConversation.addEventListener("click", startConversation);
elements.composer.addEventListener("submit", sendMessage);
elements.input.addEventListener("keydown", event => {
    if (event.key === "Enter" && !event.shiftKey) {
        event.preventDefault();
        elements.composer.requestSubmit();
    }
});

if (externalId) {
    elements.externalId.value = externalId;
    setReady(true);
    loadConversations();
    loadProfile();
}
