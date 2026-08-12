(() => {
  const WEEKDAY_ZH = ['週日', '週一', '週二', '週三', '週四', '週五', '週六'];

  function formatMMDD(date) {
    const m = String(date.getMonth() + 1).padStart(2, '0');
    const d = String(date.getDate()).padStart(2, '0');
    return `${m}/${d}`;
  }

  function isSameDate(a, b) {
    return (
      a.getFullYear() === b.getFullYear() &&
      a.getMonth() === b.getMonth() &&
      a.getDate() === b.getDate()
    );
  }

  function getStartOfWeek(date) {
    const d = new Date(date);
    d.setHours(0, 0, 0, 0);
    const day = d.getDay();
    const diff = day === 0 ? -6 : 1 - day;
    d.setDate(d.getDate() + diff);
    return d;
  }

  function updateSalesTrendsXAxis() {
    const items = Array.from(document.querySelectorAll('[data-sales-trends-xitem]'));
    if (items.length === 0) return;

    const today = new Date();
    today.setHours(0, 0, 0, 0);

    const weekStart = getStartOfWeek(today);

    for (let i = 0; i < items.length; i += 1) {
      const item = items[i];
      const labelEl = item.querySelector('[data-sales-trends-xlabel]');
      const dotEl = item.querySelector('[data-sales-trends-today-dot]');
      if (!labelEl) continue;

      const d = new Date(weekStart);
      d.setDate(weekStart.getDate() + i);

      const isWeekend = d.getDay() === 0 || d.getDay() === 6;
      const isToday = isSameDate(d, today);

      labelEl.textContent = `${formatMMDD(d)} ${WEEKDAY_ZH[d.getDay()]}`;
      labelEl.classList.toggle('font-black', isWeekend);
      labelEl.classList.toggle('text-primary', isWeekend);
      labelEl.classList.toggle('font-bold', !isWeekend);
      labelEl.classList.toggle('text-slate-500', !isWeekend);

      if (dotEl) {
        dotEl.classList.toggle('hidden', !isToday);
      }
    }
  }

  document.addEventListener('DOMContentLoaded', updateSalesTrendsXAxis);
})();
