// Admin dashboard: stats, csv import, accounts, logs, student list.

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, c =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

async function loadStats() {
  const s = await (await fetch("/api/stats")).json();
  document.getElementById("sStudents").textContent = s.students;
  document.getElementById("sToday").textContent = s.scans_today;
  document.getElementById("sOntime").textContent = s.ontime_today;
  document.getElementById("sLate").textContent = s.late_today;
}

// the <input type="time"> control is 24-hour by nature, so echo the
// 12-hour reading next to it: "08:00" -> "8:00 AM"
function hhmmTo12h(value) {
  const m = /^(\d{1,2}):(\d{2})$/.exec(value || "");
  if (!m) return "-";
  let h = Number(m[1]) % 12;
  if (h === 0) h = 12;
  return h + ":" + m[2] + " " + (Number(m[1]) < 12 ? "AM" : "PM");
}

async function loadSettings() {
  const s = await (await fetch("/api/settings")).json();
  document.getElementById("setStart").value = s.start_time;
  document.getElementById("setEarly").value = s.early_before;
  document.getElementById("setLate").value = s.late_after;
  document.getElementById("startEcho").textContent = s.start_time_12h;
}

document.getElementById("setStart").addEventListener("input", (e) => {
  document.getElementById("startEcho").textContent = hhmmTo12h(e.target.value);
});

document.getElementById("saveSettingsBtn").addEventListener("click", async () => {
  const res = await fetch("/api/settings", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      start_time: document.getElementById("setStart").value,
      early_before: Number(document.getElementById("setEarly").value),
      late_after: Number(document.getElementById("setLate").value)
    })
  });
  const data = await res.json();
  document.getElementById("settingsNote").textContent =
    data.error || "saved.";
  if (data.start_time_12h) {
    document.getElementById("startEcho").textContent = data.start_time_12h;
  }
});

async function loadLogs() {
  const logs = await (await fetch("/api/logs?n=50")).json();
  document.getElementById("logBody").innerHTML = logs.map(l =>
    "<tr><td>" + l.id + "</td><td>" + escapeHtml(l.student_id) +
    "</td><td>" + l.time + "</td><td>" + escapeHtml(l.status || "-") +
    "</td><td>" + escapeHtml(l.note || "") + "</td></tr>"
  ).join("");
}

async function loadStudents() {
  const list = await (await fetch("/api/students")).json();
  document.getElementById("studentBody").innerHTML = list.map(s =>
    "<tr><td>" + escapeHtml(s.student_id) + "</td><td>" + escapeHtml(s.full_name) +
    "</td><td>" + escapeHtml(s.course || "") + "</td><td>" + escapeHtml(s.year_level || "") +
    "</td><td>" + escapeHtml(s.section || "") + "</td><td>" + escapeHtml(s.gmail || "") +
    "</td></tr>"
  ).join("");
}

// csv import: pick a file, it uploads right away
document.getElementById("csvFile").addEventListener("change", async (e) => {
  const file = e.target.files[0];
  if (!file) return;
  const fd = new FormData();
  fd.append("file", file);
  const note = document.getElementById("importNote");
  const res = await fetch("/api/import", { method: "POST", body: fd });
  const data = await res.json();
  note.textContent = data.error
    ? "import failed: " + data.error
    : file.name + ": " + data.added + " new, " + data.updated + " updated";
  e.target.value = "";
  loadStats();
  loadStudents();
});

// accounts
function renderUsers(users) {
  document.getElementById("userList").innerHTML = users.map(u =>
    '<div class="user-row"><span>' + escapeHtml(u) + '</span>' +
    '<button class="del" data-user="' + escapeHtml(u) + '">remove</button></div>'
  ).join("");
  document.querySelectorAll(".del").forEach(btn =>
    btn.addEventListener("click", async () => {
      if (!confirm("Remove account " + btn.dataset.user + "?")) return;
      const res = await fetch("/api/accounts/delete", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username: btn.dataset.user })
      });
      const data = await res.json();
      document.getElementById("acctNote").textContent = data.error || "removed.";
      if (data.users) renderUsers(data.users);
    }));
}

document.getElementById("addUserBtn").addEventListener("click", async () => {
  const u = document.getElementById("newUser").value.trim();
  const p = document.getElementById("newPass").value;
  const res = await fetch("/api/accounts", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username: u, password: p })
  });
  const data = await res.json();
  document.getElementById("acctNote").textContent =
    data.error || ("saved " + u);
  if (data.users) renderUsers(data.users);
  document.getElementById("newUser").value = "";
  document.getElementById("newPass").value = "";
});

loadStats();
loadSettings();
loadLogs();
loadStudents();
setInterval(loadStats, 15000);
setInterval(loadLogs, 15000);
