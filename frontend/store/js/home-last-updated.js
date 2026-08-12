(() => {
  const target = document.querySelector('[data-last-updated]');
  if (!target) return;

  const pad2 = (value) => String(value).padStart(2, '0');

  const formatLocalDateTime = (date) => {
    const year = date.getFullYear();
    const month = pad2(date.getMonth() + 1);
    const day = pad2(date.getDate());
    const hours = pad2(date.getHours());
    const minutes = pad2(date.getMinutes());
    return `${year}-${month}-${day} ${hours}:${minutes}`;
  };

  const refresh = () => {
    target.textContent = formatLocalDateTime(new Date());
  };

  const rangeButtons = Array.from(document.querySelectorAll('button[data-date-range]'));
  for (const button of rangeButtons) {
    button.addEventListener('click', () => {
      refresh();
    });
  }

  refresh();
})();
