// Public scan page: verify an id, show the card. Nothing else lives here.
// Scanner guns act like a keyboard + Enter, so we just listen for Enter.

const scanInput = document.getElementById("scanInput");
const noteInput = document.getElementById("noteInput");
const result = document.getElementById("result");

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
}

function row(label, value) {
  return '<div class="row"><span>' + label + "</span><b>" + escapeHtml(value) + "</b></div>";
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
  const st = (data.scan.status || "").toUpperCase();
  const badge = st === "LATE" ? "late" : (st === "EARLY" ? "early" : "ontime");
  result.classList.add("ok");
  result.innerHTML =
    '<div class="name">&#10004; ' + escapeHtml(s.full_name) + "</div>" +
    row("Student No.", s.student_id) +
    row("Section", s.section || "-") +
    row("Time", data.scan.time) +
    '<div class="statusline"><span class="badge ' + badge + '">' + st + "</span></div>";
}

document.getElementById("scanBtn").addEventListener("click", doScan);
scanInput.addEventListener("keydown", (e) => { if (e.key === "Enter") doScan(); });
