(() => {
  const tabsRoot = document.querySelector("[data-order-tabs]");
  if (!tabsRoot) return;

  const buttons = Array.from(tabsRoot.querySelectorAll("button[data-order-filter]"));
  if (buttons.length === 0) return;

  const sections = Array.from(document.querySelectorAll("section[data-order-section]"));
  if (sections.length === 0) return;

  const emptyState = document.querySelector("[data-order-empty]");

  const ACTIVE_CLASSES = ["bg-primary/10", "text-primary"];
  const INACTIVE_CLASSES = [
    "text-slate-500",
    "hover:bg-slate-50",
    "dark:hover:bg-slate-800",
    "transition-colors",
  ];

  const knownFilters = new Set(buttons.map((b) => b.getAttribute("data-order-filter") || ""));

  let currentFilter = "all";

  const getButtonBaseLabel = (button) => {
    const existing = button.getAttribute("data-order-label");
    if (existing) return existing;

    const raw = (button.textContent || "").trim().replace(/\s+/g, " ");
    const base = raw.replace(/\s*\(\s*\d+\s*\)\s*$/, "");
    button.setAttribute("data-order-label", base);
    return base;
  };

  const getSectionOrderCount = (section) => {
    const explicit = section.querySelectorAll("[data-order-item]");
    if (explicit.length > 0) return explicit.length;

    const grid = section.querySelector(".grid");
    const candidates = grid
      ? Array.from(grid.children)
      : Array.from(section.querySelectorAll(":scope > div"));

    const isOrderCard = (el) => {
      if (!(el instanceof HTMLElement)) return false;
      const text = (el.textContent || "").trim();
      return /#\s*ORD-\d+/i.test(text);
    };

    return candidates.filter(isOrderCard).length;
  };

  const setSectionHeadingCount = (section, count) => {
    const heading = section.querySelector("h3");
    if (!heading) return;

    const countSpan = heading.querySelector("span");
    if (countSpan && /\(\s*\d+\s*\)/.test(countSpan.textContent || "")) {
      countSpan.textContent = `(${count})`;
      return;
    }

    const raw = (heading.textContent || "").trim().replace(/\s+/g, " ");
    const base = raw.replace(/\s*\(\s*\d+\s*\)\s*$/, "");
    heading.textContent = `${base} (${count})`;
  };

  const refreshCounts = () => {
    const countsByFilter = new Map();

    for (const section of sections) {
      const key = (section.getAttribute("data-order-section") || "").trim();
      if (!key) continue;
      const count = getSectionOrderCount(section);
      countsByFilter.set(key, count);
      setSectionHeadingCount(section, count);
    }

    let total = 0;
    for (const button of buttons) {
      const filter = (button.getAttribute("data-order-filter") || "").trim();
      if (!filter || filter === "all") continue;
      const count = countsByFilter.get(filter) ?? 0;
      total += count;
    }

    for (const button of buttons) {
      const filter = (button.getAttribute("data-order-filter") || "").trim();
      const base = getButtonBaseLabel(button);

      const count = filter === "all" ? total : countsByFilter.get(filter) ?? 0;
      button.textContent = `${base} (${count})`;
    }
  };

  const normalizeFilter = (raw) => {
    const value = (raw || "").trim();
    if (knownFilters.has(value)) return value;
    return "all";
  };

  const setButtonState = (button, isActive) => {
    for (const cls of ACTIVE_CLASSES) button.classList.toggle(cls, isActive);
    for (const cls of INACTIVE_CLASSES) button.classList.toggle(cls, !isActive);
    button.setAttribute("aria-selected", String(isActive));
    button.setAttribute("tabindex", isActive ? "0" : "-1");
  };

  const applyFilter = (filter) => {
    const normalized = normalizeFilter(filter);

    currentFilter = normalized;

    refreshCounts();

    for (const button of buttons) {
      const buttonFilter = button.getAttribute("data-order-filter") || "";
      setButtonState(button, buttonFilter === normalized);
    }

    let anyVisible = false;

    for (const section of sections) {
      const sectionKey = section.getAttribute("data-order-section") || "";
      const shouldShow = normalized === "all" ? true : sectionKey === normalized;
      section.classList.toggle("hidden", !shouldShow);
      if (shouldShow) anyVisible = true;
    }

    if (emptyState) {
      emptyState.classList.toggle("hidden", anyVisible);
    }

    const newHash = normalized === "all" ? "" : `#${encodeURIComponent(normalized)}`;
    if (newHash !== window.location.hash) {
      history.replaceState(null, "", window.location.pathname + window.location.search + newHash);
    }
  };

  tabsRoot.setAttribute("role", "tablist");
  for (const button of buttons) button.setAttribute("role", "tab");

  tabsRoot.addEventListener("click", (event) => {
    const target = event.target instanceof Element ? event.target.closest("button[data-order-filter]") : null;
    if (!target) return;
    applyFilter(target.getAttribute("data-order-filter"));
  });

  tabsRoot.addEventListener("keydown", (event) => {
    if (!(event.target instanceof Element)) return;
    const activeIndex = buttons.findIndex((b) => b.getAttribute("aria-selected") === "true");
    if (activeIndex < 0) return;

    let nextIndex = -1;
    if (event.key === "ArrowRight") nextIndex = (activeIndex + 1) % buttons.length;
    if (event.key === "ArrowLeft") nextIndex = (activeIndex - 1 + buttons.length) % buttons.length;
    if (event.key === "Home") nextIndex = 0;
    if (event.key === "End") nextIndex = buttons.length - 1;

    if (nextIndex === -1) return;
    event.preventDefault();

    const nextButton = buttons[nextIndex];
    nextButton.focus();
    applyFilter(nextButton.getAttribute("data-order-filter"));
  });

  const initialFromHash = decodeURIComponent((window.location.hash || "").replace(/^#/, ""));
  applyFilter(initialFromHash);

  window.addEventListener('btpro:orders-changed', () => {
    applyFilter(currentFilter);
  });
})();
