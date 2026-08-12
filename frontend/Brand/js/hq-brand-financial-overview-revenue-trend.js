(() => {
    const SVG_W = 700;
    const SVG_H = 240;
    const PADDING = { left: 36, right: 36, top: 18, bottom: 26 };

    function formatMMDD(date) {
        const d = new Date(date);
        const m = String(d.getMonth() + 1).padStart(2, '0');
        const day = String(d.getDate()).padStart(2, '0');
        return `${m}/${day}`;
    }

    function formatMD(date) {
        const d = new Date(date);
        return `${d.getMonth() + 1}/${d.getDate()}`;
    }

    function addDays(date, days) {
        const d = new Date(date);
        d.setDate(d.getDate() + days);
        return d;
    }

    // Week starts on Monday.
    function startOfWeekMonday(date) {
        const d = new Date(date);
        d.setHours(0, 0, 0, 0);
        const day = d.getDay();
        const diff = day === 0 ? -6 : 1 - day;
        d.setDate(d.getDate() + diff);
        return d;
    }

    function getCompletedWeekStarts(count) {
        const now = new Date();
        const currentWeekStart = startOfWeekMonday(now);

        // Exclude current week.
        const lastIncludedWeekStart = new Date(currentWeekStart);
        lastIncludedWeekStart.setDate(lastIncludedWeekStart.getDate() - 7);

        const dates = [];
        for (let i = count - 1; i >= 0; i -= 1) {
            const d = new Date(lastIncludedWeekStart);
            d.setDate(d.getDate() - i * 7);
            dates.push(d);
        }
        return dates;
    }

    function getCompletedWeekStartLabels(count) {
        return getCompletedWeekStarts(count).map(formatMMDD);
    }

    function clearEl(el) {
        if (!el) return;
        while (el.firstChild) el.removeChild(el.firstChild);
    }

    function clamp(value, min, max) {
        return Math.max(min, Math.min(max, value));
    }

    function formatCurrency(value) {
        const n = Number(value);
        if (!Number.isFinite(n)) return '—';
        return `$ ${n.toLocaleString('en-US')}`;
    }

    function renderGrid(gridEl) {
        if (!gridEl) return;
        clearEl(gridEl);

        const plotH = SVG_H - PADDING.top - PADDING.bottom;
        const lines = 4;
        for (let i = 0; i <= lines; i += 1) {
            const y = PADDING.top + (plotH * i) / lines;
            const line = document.createElementNS('http://www.w3.org/2000/svg', 'line');
            line.setAttribute('x1', String(PADDING.left));
            line.setAttribute('x2', String(SVG_W - PADDING.right));
            line.setAttribute('y1', String(y));
            line.setAttribute('y2', String(y));
            line.setAttribute('stroke', 'currentColor');
            line.setAttribute('stroke-width', '1');
            line.setAttribute('opacity', '0.85');
            gridEl.appendChild(line);
        }
    }

    function smoothPathD(seg) {
        if (seg.length === 1) return `M ${seg[0].x} ${seg[0].y}`;
        let d = `M ${seg[0].x} ${seg[0].y}`;
        for (let i = 1; i < seg.length; i += 1) {
            const prev = seg[i - 1];
            const curr = seg[i];
            const cpX = (prev.x + curr.x) / 2;
            d += ` C ${cpX} ${prev.y}, ${cpX} ${curr.y}, ${curr.x} ${curr.y}`;
        }
        return d;
    }

    function renderSeries(seriesEl, values) {
        if (!seriesEl) return;
        clearEl(seriesEl);

        const plotW = SVG_W - PADDING.left - PADDING.right;
        const plotH = SVG_H - PADDING.top - PADDING.bottom;

        const n = values.length;
        if (n === 0) return { points: [] };

        const definedValues = values
            .map((v) => (Number.isFinite(Number(v)) ? Number(v) : null))
            .filter((v) => v !== null);

        if (definedValues.length === 0) return { points: [] };

        const minV = Math.min(...definedValues);
        const maxV = Math.max(...definedValues);
        const span = Math.max(1, maxV - minV);

        const yMin = minV - span * 0.1;
        const yMax = maxV + span * 0.1;

        function pointFor(index, v) {
            const t = n === 1 ? 0.5 : index / (n - 1);
            const x = PADDING.left + t * plotW;
            const normalized = (Number(v) - yMin) / Math.max(1e-9, yMax - yMin);
            const y = PADDING.top + (1 - clamp(normalized, 0, 1)) * plotH;
            return { x, y };
        }

        let segment = [];
        const points = [];

        function flushSegment() {
            if (segment.length === 0) return;

            // 漸層填色區域
            const gradId = `grad-${Math.random().toString(36).slice(2)}`;
            const defs = document.createElementNS('http://www.w3.org/2000/svg', 'defs');
            const grad = document.createElementNS('http://www.w3.org/2000/svg', 'linearGradient');
            grad.setAttribute('id', gradId);
            grad.setAttribute('x1', '0'); grad.setAttribute('y1', '0');
            grad.setAttribute('x2', '0'); grad.setAttribute('y2', '1');
            const stop1 = document.createElementNS('http://www.w3.org/2000/svg', 'stop');
            stop1.setAttribute('offset', '0%');
            stop1.setAttribute('stop-color', '#ec5b13');
            stop1.setAttribute('stop-opacity', '0.18');
            const stop2 = document.createElementNS('http://www.w3.org/2000/svg', 'stop');
            stop2.setAttribute('offset', '100%');
            stop2.setAttribute('stop-color', '#ec5b13');
            stop2.setAttribute('stop-opacity', '0');
            grad.appendChild(stop1); grad.appendChild(stop2);
            defs.appendChild(grad);
            seriesEl.appendChild(defs);

            const linePath = smoothPathD(segment);
            const fillPath = linePath
                + ` L ${segment[segment.length - 1].x} ${PADDING.top + plotH}`
                + ` L ${segment[0].x} ${PADDING.top + plotH} Z`;

            const fill = document.createElementNS('http://www.w3.org/2000/svg', 'path');
            fill.setAttribute('d', fillPath);
            fill.setAttribute('fill', `url(#${gradId})`);
            fill.setAttribute('stroke', 'none');
            seriesEl.appendChild(fill);

            // 平滑曲線
            const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
            path.setAttribute('d', linePath);
            path.setAttribute('fill', 'none');
            path.setAttribute('stroke', 'currentColor');
            path.setAttribute('stroke-width', '2.5');
            path.setAttribute('stroke-linecap', 'round');
            path.setAttribute('stroke-linejoin', 'round');
            seriesEl.appendChild(path);

            // 端點圓點
            segment.forEach((p) => {
                const outer = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
                outer.setAttribute('cx', String(p.x));
                outer.setAttribute('cy', String(p.y));
                outer.setAttribute('r', '5');
                outer.setAttribute('fill', 'white');
                outer.setAttribute('stroke', 'currentColor');
                outer.setAttribute('stroke-width', '2.5');
                seriesEl.appendChild(outer);
            });

            segment = [];
        }

        for (let i = 0; i < n; i += 1) {
            const raw = values[i];
            const v = Number.isFinite(Number(raw)) ? Number(raw) : null;
            if (v === null) { flushSegment(); continue; }
            const p = pointFor(i, v);
            points.push({ index: i, x: p.x, y: p.y, value: v });
            segment.push(p);
        }
        flushSegment();

        return { points };
    }

    function setXLabels(xLabelsEl, labels) {
        if (!xLabelsEl) return;

        clearEl(xLabelsEl);

        const n = labels.length;
        const plotW = SVG_W - PADDING.left - PADDING.right;

        labels.forEach((text, i) => {
            const t = n === 1 ? 0.5 : i / (n - 1);
            const x = PADDING.left + t * plotW;
            const leftPct = (x / SVG_W) * 100;

            const span = document.createElement('span');
            span.className = 'absolute top-0 -translate-x-1/2 whitespace-nowrap';
            span.style.left = `${leftPct}%`;
            span.textContent = text;
            xLabelsEl.appendChild(span);
        });
    }

    function setActiveButton(buttons, range) {
        buttons.forEach((btn) => {
            const isActive = btn.getAttribute('data-hq-revenue-trend-range') === range;

            btn.classList.toggle('bg-primary', isActive);
            btn.classList.toggle('text-white', isActive);

            btn.classList.toggle('bg-slate-100', !isActive);
            btn.classList.toggle('dark:bg-slate-700', !isActive);
            btn.classList.toggle('text-slate-700', !isActive);
            btn.classList.toggle('dark:text-slate-200', !isActive);
        });
    }

    function hashToUnitFloat(str) {
        const s = String(str);
        let h = 2166136261;
        for (let i = 0; i < s.length; i += 1) {
            h ^= s.charCodeAt(i);
            h = Math.imul(h, 16777619);
        }
        return (h >>> 0) / 4294967295;
    }

    function generateValuesFromLabels(labels, base, amplitude) {
        return labels.map((label, i) => {
            const u = hashToUnitFloat(`${label}-${i}`);
            const delta = (u - 0.5) * 2 * amplitude;
            return Math.max(0, Math.round(base + delta));
        });
    }

    function getCompletedMonthLabels(count) {
        const now = new Date();
        const cursor = new Date(now.getFullYear(), now.getMonth(), 1);
        // Exclude current month.
        cursor.setMonth(cursor.getMonth() - 1);

        const labels = [];
        for (let i = 0; i < count; i += 1) {
            labels.push(`${cursor.getMonth() + 1}月`);
            cursor.setMonth(cursor.getMonth() - 1);
        }
        return labels.reverse();
    }

    // Custom seasons: Spring(3-5), Summer(6-8), Fall(9-11), Winter(12-2)
    // Returns last 4 seasons as fallback data (oldest → newest)
    function getCustomSeasonSeries() {
        const now = new Date();
        const month = now.getMonth() + 1; // 1-12
        let qIdx = month >= 3 && month <= 5 ? 1
                 : month >= 6 && month <= 8 ? 2
                 : month >= 9 && month <= 11 ? 3 : 0;
        let qYear = now.getFullYear();

        const seasons = [];
        for (let i = 0; i < 4; i++) {
            let label;
            if (qIdx === 0) label = '12-2月';
            else if (qIdx === 1) label = '3-5月';
            else if (qIdx === 2) label = '6-8月';
            else label = '9-11月';
            seasons.unshift(label); // prepend for oldest-first
            qIdx--;
            if (qIdx < 0) { qIdx = 3; qYear--; }
        }
        return {
            labels: seasons,
            values: generateValuesFromLabels(seasons, 8_200_000, 1_000_000),
        };
    }

    function getSeries(range) {
        if (range === 'week') {
            const weekStarts = getCompletedWeekStarts(6);
            const labels = weekStarts.map(formatMMDD);
            return {
                labels,
                values: generateValuesFromLabels(labels, 800_000, 120_000),
                meta: { weekStarts },
            };
        }

        if (range === 'quarter') {
            return getCustomSeasonSeries();
        }

        // month (default)
        {
            const labels = getCompletedMonthLabels(6);
            return {
                labels,
                values: generateValuesFromLabels(labels, 2_700_000, 500_000),
            };
        }
    }

    function inferInitialRange(buttons) {
        const active = buttons.find((b) => b.classList.contains('bg-primary'));
        const v = active?.getAttribute('data-hq-revenue-trend-range');
        if (v === 'week' || v === 'month' || v === 'quarter') return v;
        return 'month';
    }

    function init() {
        const buttons = Array.from(document.querySelectorAll('[data-hq-revenue-trend-range]'));
        const gridEl = document.querySelector('[data-hq-revenue-trend-grid]');
        const seriesEl = document.querySelector('[data-hq-revenue-trend-series]');
        const xLabelsEl = document.querySelector('[data-hq-revenue-trend-xlabels]');
        const svgEl = document.querySelector('[data-hq-revenue-trend-svg]');
        const tooltipEl = document.querySelector('[data-hq-revenue-trend-tooltip]');

        if (buttons.length === 0 || !gridEl || !seriesEl || !xLabelsEl || !svgEl || !tooltipEl) return;

        renderGrid(gridEl);

        let range = inferInitialRange(buttons);
        let currentLabels = [];
        let currentValues = [];
        let currentPoints = [];
        let currentMeta = null;

        function hideTooltip() {
            tooltipEl.classList.add('hidden');
        }

        function showTooltipForPoint(point) {
            let label = currentLabels[point.index] ?? '';
            if (range === 'week') {
                const weekStarts = currentMeta?.weekStarts;
                const start = Array.isArray(weekStarts) ? weekStarts[point.index] : null;
                if (start) {
                    const end = addDays(start, 6);
                    label = `${formatMD(start)}-${formatMD(end)}`;
                }
            }

            tooltipEl.textContent = `${label}｜營收：${formatCurrency(point.value)}`;

            const leftPct = (point.x / SVG_W) * 100;
            const topPct = (point.y / SVG_H) * 100;

            const clampedLeft = clamp(leftPct, 2, 98);
            const clampedTop = clamp(topPct, 8, 98);

            tooltipEl.style.left = `${clampedLeft}%`;
            tooltipEl.style.top = `${clampedTop}%`;
            tooltipEl.style.transform = 'translate(-50%, -120%)';
            tooltipEl.classList.remove('hidden');
        }

        function nearestPointByX(svgX) {
            if (!Array.isArray(currentPoints) || currentPoints.length === 0) return null;

            let best = currentPoints[0];
            let bestDist = Math.abs(svgX - best.x);
            for (let i = 1; i < currentPoints.length; i += 1) {
                const p = currentPoints[i];
                const d = Math.abs(svgX - p.x);
                if (d < bestDist) {
                    best = p;
                    bestDist = d;
                }
            }
            return best;
        }

        function applyData(labels, values, meta) {
            currentLabels = labels;
            currentValues = values;
            currentMeta = meta ?? null;
            setXLabels(xLabelsEl, labels);
            const rendered = renderSeries(seriesEl, values);
            currentPoints = rendered?.points ?? [];
            hideTooltip();
        }

        function setChartOpacity(opacity, animated) {
            if (!svgEl) return;
            svgEl.style.transition = animated ? 'opacity 0.15s ease' : '';
            svgEl.style.opacity = String(opacity);
        }

        async function sync() {
            setActiveButton(buttons, range);
            hideTooltip();
            setChartOpacity(0.25, true);

            try {
                if (typeof BrandAPI !== 'undefined' && BrandAPI.getFinanceTrend) {
                    const api = await BrandAPI.getFinanceTrend(range);
                    if (api && Array.isArray(api.labels) && Array.isArray(api.values)) {
                        applyData(api.labels, api.values, null);
                        setChartOpacity(1, true);
                        return;
                    }
                }
            } catch (e) {
                console.warn('趨勢圖 API 載入失敗，使用本地資料', e);
            }

            // fallback
            const local = getSeries(range);
            applyData(local.labels, local.values, local.meta ?? null);
            setChartOpacity(1, true);
        }

        svgEl.addEventListener('mousemove', (e) => {
            const rect = svgEl.getBoundingClientRect();
            if (rect.width <= 0) return;

            const x = e.clientX - rect.left;
            const svgX = (x / rect.width) * SVG_W;

            const p = nearestPointByX(svgX);
            if (!p) {
                hideTooltip();
                return;
            }

            showTooltipForPoint(p);
        });

        svgEl.addEventListener('mouseleave', hideTooltip);

        buttons.forEach((btn) => {
            btn.addEventListener('click', () => {
                const next = btn.getAttribute('data-hq-revenue-trend-range');
                if (next !== 'week' && next !== 'month' && next !== 'quarter') return;
                range = next;
                sync().catch(() => {});
            });
        });

        sync().catch(() => {});
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
