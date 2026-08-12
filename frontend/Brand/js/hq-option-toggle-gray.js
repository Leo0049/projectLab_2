/* Toggle OFF => grey-out option, ON => restore.
 * Used by HQ brand menu pages:
 * - hq-menu-overview.html
 * - hq-specs-and-toppings-management.html
 */

(function () {
    "use strict";

    function isSwitchInput(input) {
        if (!(input instanceof HTMLInputElement)) return false;
        if (input.type !== "checkbox") return false;
        if (!input.classList.contains("peer")) return false;
        if (!input.classList.contains("sr-only")) return false;

        var next = input.nextElementSibling;
        if (!next) return false;
        // Most switches in this project use a fixed-size track: w-9 h-5
        if (!next.classList.contains("w-9")) return false;

        return true;
    }

    function syncForMenuCard(input) {
        var card = input.closest(".group");
        if (!card) return;

        var enabled = input.checked;
        card.classList.toggle("opacity-60", !enabled);

        // Optional: match existing greyed-out style by greyscaling the thumbnail.
        var thumb = card.querySelector(".w-12.h-12");
        if (thumb) {
            thumb.classList.toggle("grayscale", !enabled);
        }

        // Match existing disabled example: price uses grey text when OFF.
        var price = card.querySelector("p.text-sm.font-bold");
        if (price) {
            if (!enabled) {
                price.classList.remove("text-primary");
                price.classList.add("text-slate-400");
            } else {
                price.classList.remove("text-slate-400");
                price.classList.add("text-primary");
            }
        }
    }

    function syncForTableRow(input) {
        var row = input.closest("tr");
        if (!row) return;

        var enabled = input.checked;
        row.classList.toggle("opacity-60", !enabled);

        // Update the status label text next to the switch.
        // Expected markup: <input ...><div ...></div></div><span>已啟用</span>
        var wrapper = input.parentElement;
        if (wrapper) {
            var statusText = wrapper.nextElementSibling;
            if (statusText && statusText.tagName === "SPAN") {
                statusText.textContent = enabled ? "已啟用" : "已停用";
            }
        }
    }

    function sync(input) {
        if (!isSwitchInput(input)) return;

        // Prefer table-row behavior when inside a table.
        if (input.closest("tr")) {
            syncForTableRow(input);
            return;
        }

        syncForMenuCard(input);
    }

    function init() {
        var inputs = document.querySelectorAll('input[type="checkbox"].sr-only.peer');
        if (!inputs.length) return;

        inputs.forEach(function (input) {
            if (!isSwitchInput(input)) return;

            sync(input);
            input.addEventListener("change", function () {
                sync(input);
            });
        });
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", init);
    } else {
        init();
    }
})();
