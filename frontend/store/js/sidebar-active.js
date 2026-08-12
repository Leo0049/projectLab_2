(() => {
  const ACTIVE_CLASS =
    "flex items-center gap-3 px-3 py-2.5 rounded-lg bg-primary text-white text-base font-medium shadow-lg shadow-primary/20";
  const INACTIVE_CLASS =
    "flex items-center gap-3 px-3 py-2.5 rounded-lg text-base font-medium text-slate-600 dark:text-slate-400 hover:bg-primary/10 hover:text-primary transition-colors";

  const LABEL_BY_ICON = {
    dashboard: "首頁",
    store: "商店管理",
    menu_book: "菜單管理",
    assignment: "訂單管理",
    payments: "財務管理",
    monitoring: "數據報表",
  };

  const HREF_BY_ICON = {
    dashboard: "home.html",
    store: "store-basic-info.html",
    menu_book: "menu-drinks.html",
    assignment: "order-all.html",
    payments: "finance-revenue-overview.html",
    monitoring: "reports-business-overview.html",
  };

  const filename = (() => {
    try {
      const path = window.location.pathname || "";
      const parts = path.split("/").filter(Boolean);
      return parts.length ? parts[parts.length - 1] : "";
    } catch {
      return "";
    }
  })();

  const getTargetIcon = (name) => {
    const lower = (name || "").toLowerCase();
    if (lower === "home.html") return "dashboard";
    if (lower.startsWith("store-")) return "store";
    if (lower.startsWith("menu-")) return "menu_book";
    if (lower.startsWith("order-")) return "assignment";
    if (lower.startsWith("finance-")) return "payments";
    if (lower.startsWith("reports-")) return "monitoring";
    return null;
  };

  const targetIcon = getTargetIcon(filename);

  const headerIcon = document.getElementById("headerPageIcon");
  const headerLabel = document.getElementById("headerPageLabel");
  if (targetIcon && headerIcon && headerLabel) {
    headerIcon.textContent = targetIcon;
    headerLabel.textContent = LABEL_BY_ICON[targetIcon] || headerLabel.textContent;
  }

  // 右上角顯示頁面名稱（不顯示店名，首頁才顯示店名）
  const pageNameSpan = document.querySelector("header span.whitespace-nowrap");
  if (pageNameSpan && targetIcon && targetIcon !== "dashboard") {
    pageNameSpan.textContent = LABEL_BY_ICON[targetIcon] || pageNameSpan.textContent;
  }

  const nav = document.querySelector("aside nav");
  if (!nav || !targetIcon) return;

  const links = Array.from(nav.querySelectorAll("a"));
  if (!links.length) return;

  for (const link of links) {
    const icon = link.querySelector("span.material-symbols-outlined");
    const iconText = (icon?.textContent || "").trim();
    const isActive = iconText === targetIcon;

    if (iconText && HREF_BY_ICON[iconText]) {
      link.setAttribute("href", HREF_BY_ICON[iconText]);
    }

    link.className = isActive ? ACTIVE_CLASS : INACTIVE_CLASS;
    if (isActive) {
      link.setAttribute("aria-current", "page");
    } else {
      link.removeAttribute("aria-current");
    }
  }
})();
