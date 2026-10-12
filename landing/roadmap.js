(function () {
  var PHASES = [
    { id: 0, n: "PHASE 0", name: "Gallery on your Wi-Fi", about: "Browse, zoom, download and trash photos from a browser.", status: "built", statusText: "BUILT", backend: "None", risk: "Low" },
    { id: 1, n: "PHASE 1", name: "Phone Link tier", about: "Dashboard tabs for notifications, messages, files and status, with passcode and per-feature switches.", status: "plan", statusText: "PROPOSED", backend: "None", risk: "Medium" },
    { id: 2, n: "PHASE 2", name: "Accounts and pairing", about: "Sign in, QR pairing, nickname and passcode channels, an online device list.", status: "plan", statusText: "PROPOSED", backend: "Small", risk: "Medium" },
    { id: 3, n: "PHASE 3", name: "Anywhere access", about: "Peer-to-peer WebRTC with a relay fallback and end-to-end encryption.", status: "plan", statusText: "PROPOSED", backend: "Signaling + TURN", risk: "High" },
    { id: 4, n: "PHASE 4", name: "AirMirror tier", about: "Screen mirroring, tap control, remote camera and find phone.", status: "hard", statusText: "STRETCH", backend: "Same as 3", risk: "High" }
  ];

  // lvl: full | half | none | unk
  var FEATURES = [
    { g: "Photos" },
    { f: "Full-library browse and zoom", s: "Grid, viewer, 50% to 400% zoom", pl: ["half", "Recent only"], ad: ["full", "Yes"], me: ["full", "Built"], p: 0 },
    { f: "Filters and sort", s: "Screenshots, large files, newest or oldest", pl: ["none", "No"], ad: ["half", "Partial"], me: ["full", "Built"], p: 0 },
    { f: "Bulk download as zip", s: "Streams straight to disk", pl: ["none", "One by one"], ad: ["full", "Yes"], me: ["full", "Built"], p: 0 },
    { f: "Move to trash", s: "Confirmed on the phone, 30-day recovery", pl: ["none", "No"], ad: ["half", "Delete"], me: ["full", "Built"], p: 0 },
    { f: "Works in any browser", s: "No Windows app or account", pl: ["none", "Windows app"], ad: ["full", "Yes"], me: ["full", "Built"], p: 0 },
    { g: "Device and messages" },
    { f: "Passcode and per-feature switches", s: "Sensitive tabs stay locked", pl: ["unk", "?"], ad: ["unk", "?"], me: ["none", "Not yet"], p: 1 },
    { f: "Device status", s: "Battery, Wi-Fi, storage, name", pl: ["full", "Yes"], ad: ["full", "Yes"], me: ["none", "Not yet"], p: 1 },
    { f: "Notification mirror and dismiss", s: "Needs notification access in Settings", pl: ["full", "Yes"], ad: ["full", "Yes"], me: ["none", "Not yet"], p: 1 },
    { f: "Reply from notifications", s: "Where the app offers a reply action", pl: ["full", "Yes"], ad: ["half", "Some apps"], me: ["none", "Not yet"], p: 1 },
    { f: "SMS read and send", s: "Sideload only", pl: ["full", "Yes"], ad: ["full", "Yes"], me: ["none", "Not yet"], p: 1 },
    { f: "Contacts", s: "Names on threads, searchable list", pl: ["full", "Yes"], ad: ["full", "Yes"], me: ["none", "Not yet"], p: 1 },
    { f: "Files both ways", s: "Drag to phone, download from phone", pl: ["half", "Drag and drop"], ad: ["full", "Yes"], me: ["none", "Not yet"], p: 1 },
    { f: "Clipboard sync", s: "Button or share action; Android limits background reads", pl: ["half", "Partial"], ad: ["full", "Yes"], me: ["none", "Not yet"], p: 1 },
    { f: "Find and ring phone", pl: ["half", "Ring"], ad: ["full", "Yes"], me: ["none", "Not yet"], p: 1 },
    { g: "Accounts and reach" },
    { f: "Accounts and device list", s: "Online or offline per phone", pl: ["full", "Microsoft"], ad: ["full", "Yes"], me: ["none", "Not yet"], p: 2 },
    { f: "Nickname + passcode channels", s: "Pick a name, others need the passcode", pl: ["none", "No"], ad: ["half", "Device name"], me: ["none", "Not yet"], p: 2 },
    { f: "Access from anywhere", s: "Different network, any location", pl: ["half", "Limited"], ad: ["full", "Yes"], me: ["none", "Not yet"], p: 3 },
    { f: "End-to-end encryption", s: "Server never reads your data", pl: ["unk", "?"], ad: ["unk", "?"], me: ["none", "Not yet"], p: 3 },
    { g: "Remote control" },
    { f: "Screen mirror and tap control", s: "Screen capture plus Accessibility service", pl: ["half", "Apps window"], ad: ["full", "Yes"], me: ["none", "Not yet"], p: 4 },
    { f: "Remote camera", pl: ["none", "No"], ad: ["full", "Yes"], me: ["none", "Not yet"], p: 4 },
    { f: "Calls from the PC", s: "Show and answer or decline remotely; no PC audio", pl: ["full", "Yes"], ad: ["half", "Dial and logs"], me: ["none", "Stretch"], p: 4 }
  ];

  var selected = "all";
  var spine = document.getElementById("spine");
  var chips = document.getElementById("chips");
  var rows = document.getElementById("rows");

  function el(tag, cls, text) { var e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; }
  function mark(v) {
    var s = el("span", "mark");
    s.appendChild(el("span", "dot " + v[0]));
    s.appendChild(document.createTextNode(v[1]));
    return s;
  }

  // phases
  PHASES.forEach(function (p) {
    var b = el("button", "phase"); b.type = "button"; b.setAttribute("aria-pressed", "false"); b.dataset.p = p.id;
    b.appendChild(el("span", "n", p.n));
    b.appendChild(el("span", "pill self-start " + p.status, p.statusText));
    b.appendChild(el("h3", null, p.name));
    b.appendChild(el("p", null, p.about));
    var dl = el("dl");
    [["Backend", p.backend], ["Risk", p.risk]].forEach(function (kv) {
      var d = el("div"); d.appendChild(el("dt", null, kv[0])); d.appendChild(el("dd", null, kv[1])); dl.appendChild(d);
    });
    b.appendChild(dl);
    b.addEventListener("click", function () { setFilter(String(selected) === String(p.id) ? "all" : p.id); });
    spine.appendChild(b);
  });

  // chips
  var chipDefs = [["all", "All"]].concat(PHASES.map(function (p) { return [p.id, "Phase " + p.id]; }));
  chipDefs.forEach(function (c) {
    var b = el("button", "chip", c[1]); b.type = "button"; b.dataset.p = c[0]; b.setAttribute("aria-pressed", "false");
    b.addEventListener("click", function () { setFilter(c[0]); });
    chips.appendChild(b);
  });

  // rows
  var count = 0;
  FEATURES.forEach(function (r) {
    var tr = el("tr");
    if (r.g) {
      tr.className = "group"; var td = el("td", null, r.g); td.colSpan = 5; tr.appendChild(td);
      tr.dataset.group = "1";
    } else {
      count++;
      tr.dataset.p = r.p;
      var c1 = el("td", "feat"); c1.appendChild(document.createTextNode(r.f));
      if (r.s) c1.appendChild(el("small", null, r.s));
      tr.appendChild(c1);
      [r.pl, r.ad, r.me].forEach(function (v) { var td = el("td"); td.appendChild(mark(v)); tr.appendChild(td); });
      var tg = el("td", "tgt"); var ph = PHASES[r.p];
      tg.appendChild(el("span", "pill " + ph.status, r.p === 0 ? "BUILT" : "PHASE " + r.p)); tr.appendChild(tg);
    }
    rows.appendChild(tr);
  });
  document.getElementById("featCount").textContent = count;

  function setFilter(v) {
    selected = v;
    Array.prototype.forEach.call(spine.children, function (b) { b.setAttribute("aria-pressed", String(String(b.dataset.p) === String(v))); });
    Array.prototype.forEach.call(chips.children, function (b) { b.setAttribute("aria-pressed", String(String(b.dataset.p) === String(v))); });
    var trs = rows.children, groupHasVisible = {}, lastGroup = null;
    Array.prototype.forEach.call(trs, function (tr) {
      if (tr.dataset.group) { lastGroup = tr; groupHasVisible[trs.length] = 0; tr._vis = 0; return; }
      var show = (v === "all") || String(tr.dataset.p) === String(v);
      tr.classList.remove("dim"); tr.hidden = !show;
      if (show && lastGroup) lastGroup._vis++;
    });
    Array.prototype.forEach.call(trs, function (tr) { if (tr.dataset.group) tr.hidden = !(tr._vis > 0); });
  }
  setFilter("all");
})();
