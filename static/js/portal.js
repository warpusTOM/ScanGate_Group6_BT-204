// IDCheck page logic: scan -> show card -> refresh logs.
// Scanner guns act like a keyboard + Enter, so we just listen for Enter.

const scanInput = document.getElementById("scanInput");
const noteInput = document.getElementById("noteInput");
const result = document.getElementById("result");
const logBody = document.getElementById("logBody");
const statPill = document.getElementById("statPill");

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, c =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

async function doScan() {
  const id = scanInput.value.trim();
  if (!id) return;
  try {
    const res = await fetch("/api/scan", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ id: id, note: noteInput.value.trim() })
    });
    showResult(await res.json());
  } catch (err) {
    result.classList.remove("hidden", "ok");
    result.classList.add("bad");
    result.textContent = "Cannot reach the server. Is the app still running?";
  }
  scanInput.value = "";
  scanInput.focus();
  loadLogs();
  loadStats();
}

function showResult(data) {
  result.classList.remove("hidden", "ok", "bad");
  if (!data.found) {
    result.classList.add("bad");
    result.innerHTML = "<b>NOT REGISTERED</b><br>No student with number <b>" +
      escapeHtml(data.id || "") + "</b> in the database.";
    return;
  }
  const s = data.student;
  const courseLine = [s.course, s.year_level, s.section].filter(Boolean).join(" ");
  result.classList.add("ok");
  result.innerHTML =
    '<div class="name">&#10004; ' + escapeHtml(s.full_name) + "</div>" +
    row("Student No.", s.student_id) +
    row("Course", courseLine || "-") +
    row("Gmail", s.gmail || "none on file") +
    row("Time", data.scan.time) +
    row("Email notice", data.scan.emailed ? "sent" : "not sent");
}

function row(label, value) {
  return '<div class="row"><span>' + label + "</span><b>" + escapeHtml(value) + "</b></div>";
}

async function loadLogs() {
  const logs = await (await fetch("/api/logs?n=30")).json();
  logBody.innerHTML = logs.map(l =>
    "<tr><td>" + l.id + "</td><td>" + escapeHtml(l.student_id) +
    "</td><td>" + l.time + "</td><td>" + escapeHtml(l.note || "") +
    "</td><td>" + (l.emailed ? "sent" : "-") + "</td></tr>"
  ).join("");
}

async function loadStats() {
  const s = await (await fetch("/api/stats")).json();
  statPill.textContent = s.students + " students | " + s.scans_today +
    " scans today | email " + (s.email_enabled ? "ON" : "OFF");
}

// CSV import: pick a file, it uploads right away
document.getElementById("csvFile").addEventListener("change", async (e) => {
  const file = e.target.files[0];
  if (!file) return;
  const fd = new FormData();
  fd.append("file", file);
  const res = await fetch("/api/import", { method: "POST", body: fd });
  const data = await res.json();
  const note = document.getElementById("fileNote");
  if (data.error) {
    note.textContent = "import failed: " + data.error;
  } else {
    note.textContent = "imported " + file.name + " (" + data.added +
      " new, " + data.updated + " updated)";
  }
  e.target.value = "";
  loadStats();
});

document.getElementById("scanBtn").addEventListener("click", doScan);
scanInput.addEventListener("keydown", (e) => { if (e.key === "Enter") doScan(); });

loadLogs();
loadStats();
setInterval(loadStats, 15000);
