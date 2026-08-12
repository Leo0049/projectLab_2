/**
 * Location Modal Logic
 * 地點選擇彈窗 — 縣市/行政區下拉、GPS 定位狀態、地址同步
 * 依賴：official-map-logic.js（需先載入）
 */
document.addEventListener('DOMContentLoaded', () => {
  const taiwanRegions = {
    "台北市": ["中正區","大同區","中山區","松山區","大安區","萬華區","信義區","士林區","北投區","內湖區","南港區","文山區"],
    "新北市": ["板橋區","三重區","中和區","永和區","新莊區","新店區","樹林區","鶯歌區","三峽區","淡水區","汐止區","瑞芳區","土城區","蘆洲區","五股區","泰山區","林口區","深坑區","石碇區","坪林區","三芝區","石門區","八里區","平溪區","雙溪區","貢寮區","金山區","萬里區","烏來區"],
    "桃園市": ["桃園區","中壢區","大溪區","楊梅區","蘆竹區","大園區","龜山區","八德區","龍潭區","平鎮區","新屋區","觀音區","復興區"],
    "台中市": ["中區","東區","南區","西區","北區","北屯區","西屯區","南屯區","太平區","大里區","霧峰區","烏日區","豐原區","后里區","石岡區","東勢區","和平區","新社區","潭子區","大雅區","神岡區","大肚區","沙鹿區","龍井區","梧棲區","清水區","大甲區","外埔區","大安區"],
    "台南市": ["中西區","東區","南區","北區","安平區","安南區","永康區","歸仁區","新化區","左鎮區","玉井區","楠西區","南化區","仁德區","關廟區","龍崎區","官田區","麻豆區","佳里區","西港區","七股區","將軍區","學甲區","北門區","新營區","後壁區","白河區","東山區","六甲區","下營區","柳營區","鹽水區","善化區","大內區","山上區","新市區","安定區"],
    "高雄市": ["楠梓區","左營區","鼓山區","三民區","鹽埕區","前金區","新興區","苓雅區","前鎮區","旗津區","小港區","鳳山區","林園區","大寮區","大樹區","大社區","仁武區","鳥松區","岡山區","橋頭區","燕巢區","田寮區","阿蓮區","路竹區","湖內區","茄萣區","永安區","彌陀區","梓官區","旗山區","美濃區","六龜區","甲仙區","杉林區","內門區","茂林區","桃源區","那瑪夏區"],
    "基隆市": ["仁愛區","信義區","中正區","中山區","安樂區","暖暖區","七堵區"],
    "新竹市": ["東區","北區","香山區"],
    "嘉義市": ["東區","西區"],
    "新竹縣": ["竹北市","竹東鎮","新埔鎮","關西鎮","湖口鄉","新豐鄉","芎林鄉","橫山鄉","北埔鄉","寶山鄉","峨眉鄉","尖石鄉","五峰鄉"],
    "苗栗縣": ["苗栗市","苑裡鎮","通霄鎮","竹南鎮","頭份市","後龍鎮","卓蘭鎮","大湖鄉","公館鄉","銅鑼鄉","南庄鄉","頭屋鄉","三義鄉","西湖鄉","造橋鄉","三灣鄉","獅潭鄉","泰安鄉"],
    "彰化縣": ["彰化市","鹿港鎮","和美鎮","線西鄉","伸港鄉","福興鄉","秀水鄉","花壇鄉","芬園鄉","員林市","溪湖鎮","田中鎮","大村鄉","埔鹽鄉","埔心鄉","永靖鄉","社頭鄉","二水鄉","北斗鎮","二林鎮","田尾鄉","埤頭鄉","芳苑鄉","大城鄉","竹塘鄉","溪州鄉"],
    "南投縣": ["南投市","埔里鎮","草屯鎮","竹山鎮","集集鎮","名間鄉","鹿谷鄉","中寮鄉","魚池鄉","國姓鄉","水里鄉","信義鄉","仁愛鄉"],
    "雲林縣": ["斗六市","斗南鎮","虎尾鎮","西螺鎮","土庫鎮","北港鎮","古坑鄉","大埤鄉","莿桐鄉","林內鄉","二崙鄉","崙背鄉","麥寮鄉","東勢鄉","褒忠鄉","臺西鄉","元長鄉","四湖鄉","口湖鄉","水林鄉"],
    "嘉義縣": ["太保市","朴子市","布袋鎮","大林鎮","民雄鄉","溪口鄉","新港鄉","六腳鄉","東石鄉","義竹鄉","鹿草鄉","水上鄉","中埔鄉","竹崎鄉","梅山鄉","番路鄉","大埔鄉","阿里山鄉"],
    "屏東縣": ["屏東市","潮州鎮","東港鎮","恆春鎮","萬丹鄉","長治鄉","麟洛鄉","九如鄉","里港鄉","鹽埔鄉","高樹鄉","萬巒鄉","內埔鄉","竹田鄉","新埤鄉","枋寮鄉","新園鄉","崁頂鄉","林邊鄉","南州鄉","佳冬鄉","琉球鄉","車城鄉","滿州鄉","枋山鄉","三地門鄉","霧臺鄉","瑪家鄉","泰武鄉","來義鄉","春日鄉","獅子鄉","牡丹鄉"],
    "宜蘭縣": ["宜蘭市","羅東鎮","蘇澳鎮","頭城鎮","礁溪鄉","壯圍鄉","員山鄉","冬山鄉","五結鄉","三星鄉","大同鄉","南澳鄉"],
    "花蓮縣": ["花蓮市","鳳林鎮","玉里鎮","新城鄉","吉安鄉","壽豐鄉","光復鄉","豐濱鄉","瑞穗鄉","富里鄉","秀林鄉","萬榮鄉","卓溪鄉"],
    "臺東縣": ["臺東市","成功鎮","關山鎮","卑南鄉","大武鄉","太麻里鄉","東河鄉","長濱鄉","鹿野鄉","池上鄉","綠島鄉","延平鄉","海端鄉","達仁鄉","金峰鄉","蘭嶼鄉"],
    "澎湖縣": ["馬公市","湖西鄉","白沙鄉","西嶼鄉","望安鄉","七美鄉"],
    "金門縣": ["金城鎮","金湖鎮","金沙鎮","金寧鄉","烈嶼鄉","烏坵鄉"],
    "連江縣": ["南竿鄉","北竿鄉","莒光鄉","東引鄉"]
  };

  function setupModalDropdown(inputId, listId, initialOptions, onSelectCallback) {
    const input = document.getElementById(inputId);
    const list = document.getElementById(listId);
    if (!input || !list) return null;
    let options = initialOptions;
    let currentValidValue = '';

    function renderList(filterText) {
      const filtered = options.filter(opt => opt.includes(filterText || ''));
      list.innerHTML = filtered.length === 0
        ? '<div class="p-3 text-sm text-gray-500 text-center">找不到相符的結果</div>'
        : filtered.map(opt =>
            `<div class="dropdown-item p-3 px-4 text-sm font-medium text-slate-700 hover:bg-orange-50 hover:text-brand-orange cursor-pointer transition-colors" data-value="${opt}">${opt}</div>`
          ).join('');
      list.querySelectorAll('.dropdown-item').forEach(item => {
        item.addEventListener('mousedown', e => {
          e.preventDefault();
          const val = e.currentTarget.dataset.value;
          input.value = val;
          currentValidValue = val;
          list.classList.add('hidden');
          if (onSelectCallback) onSelectCallback(val);
        });
      });
    }

    input.addEventListener('focus', () => { renderList(input.value); list.classList.remove('hidden'); });
    input.addEventListener('input', e => { renderList(e.target.value); list.classList.remove('hidden'); });
    input.addEventListener('blur', () => {
      list.classList.add('hidden');
      if (!options.includes(input.value)) {
        input.value = currentValidValue;
      } else if (input.value !== currentValidValue) {
        currentValidValue = input.value;
        if (onSelectCallback) onSelectCallback(currentValidValue);
      }
    });

    return {
      setValue(val) { if (options.includes(val)) { input.value = val; currentValidValue = val; if (onSelectCallback) onSelectCallback(val); } },
      setOptions(newOptions) { options = newOptions; input.value = ''; currentValidValue = ''; },
      getValue() { return currentValidValue; }
    };
  }

  function resetGpsStatus() {
    const gpsRow = document.getElementById('modal-gps-status-row');
    const gpsIndicator = document.getElementById('modal-gps-indicator');
    const gpsText = document.getElementById('modal-gps-status-text');
    if (gpsRow) { gpsRow.classList.remove('border-brand-orange', 'bg-orange-50'); gpsRow.classList.add('border-gray-200', 'bg-white'); }
    if (gpsIndicator) { gpsIndicator.classList.remove('border-brand-orange', 'bg-brand-orange'); gpsIndicator.classList.add('border-gray-300'); gpsIndicator.innerHTML = ''; }
    if (gpsText) { gpsText.textContent = '尚未定位'; gpsText.classList.remove('text-brand-dark', 'font-medium'); gpsText.classList.add('text-gray-400'); }
  }
  // 暴露給 nav-auth.js 的常用地址按鈕使用
  window._locationModalResetGps = resetGpsStatus;

  // 暴露給 nav-auth.js：將城市/行政區/街道填入彈窗欄位
  window._fillLocationModalAddress = (city, district, street) => {
    const streetInput = document.getElementById('modal-street');
    if (streetInput) streetInput.value = street || '';
    if (city && modalCityCtrl) {
      modalCityCtrl.setValue(city);
      if (district && modalDistrictCtrl) {
        modalDistrictCtrl.setValue(district);
      }
    } else if (modalCityCtrl) {
      modalCityCtrl.setValue('');
    }
    syncModalAddress();
  };

  function syncModalAddress() {
    const city = modalCityCtrl ? modalCityCtrl.getValue() : '';
    const district = modalDistrictCtrl ? modalDistrictCtrl.getValue() : '';
    const street = (document.getElementById('modal-street')?.value || '').trim();
    // 組合完整地址：縣市 + 行政區 + 街道，供 geocoding 使用
    const composed = [city, district, street].filter(Boolean).join('');
    const hidden = document.getElementById('modal-address-input');
    // 手動輸入切換 → 重置 GPS 反映欄位，並清除舊座標
    resetGpsStatus();
    if (hidden) {
      if (composed) hidden.value = composed;
      delete hidden.dataset.lat;
      delete hidden.dataset.lng;
    }
  }

  // 解析 Nominatim display_name，拆出 {city, district, street}
  // 例："74號, 公益路二段, 大業里, 南屯區, 臺中市, 408, 臺灣"
  //   → { city:'台中市', district:'南屯區', street:'大業里公益路二段74號' }
  function parseNominatimAddress(raw) {
    if (!raw) return { city: '', district: '', street: '' };
    const norm = s => (s || '').replace(/臺/g, '台');
    const parts = raw.split(', ');
    const cityKeys = Object.keys(taiwanRegions);

    // 找縣市
    let cityIndex = -1, cityKey = '';
    for (let i = 0; i < parts.length; i++) {
      const n = norm(parts[i]);
      if (cityKeys.includes(n)) { cityIndex = i; cityKey = n; break; }
    }

    if (cityIndex === -1) {
      // 找不到縣市，退回舊格式：取前 (length-4) 欄反轉
      const street = parts.length >= 5
        ? parts.slice(0, parts.length - 4).reverse().join('')
        : parts[0] || raw;
      return { city: '', district: '', street };
    }

    // 找行政區：縣市前一個且存在於 taiwanRegions 清單中
    let districtIndex = -1, districtKey = '';
    if (cityIndex > 0) {
      const candidate = norm(parts[cityIndex - 1]);
      if ((taiwanRegions[cityKey] || []).includes(candidate)) {
        districtIndex = cityIndex - 1;
        districtKey = candidate;
      }
    }

    // 街道：行政區（或縣市）之前的所有段落，反轉後合併
    const endIdx = districtIndex >= 0 ? districtIndex : cityIndex;
    const street = parts.slice(0, endIdx).reverse().join('');
    return { city: cityKey, district: districtKey, street };
  }

  // Override MapLogic.reverseGeocode：
  // 解析後分別填入縣市下拉、行政區下拉、地址輸入框
  // 回傳街道部分，讓 nav-auth.js 的 nav bar 只顯示街道
  if (window.MapLogic) {
    const _origReverseGeocode = window.MapLogic.reverseGeocode.bind(window.MapLogic);
    window.MapLogic.reverseGeocode = async (lat, lng) => {
      const raw = await _origReverseGeocode(lat, lng);
      if (!raw) return raw;
      const { city, district, street } = parseNominatimAddress(raw);
      const displayStreet = street || raw;

      const streetInput = document.getElementById('modal-street');
      if (streetInput) streetInput.value = displayStreet;

      // 填入縣市下拉（setValue 內部的 callback 會自動更新行政區選項清單）
      if (city && modalCityCtrl) {
        modalCityCtrl.setValue(city);
        // 填入行政區下拉
        if (district && modalDistrictCtrl) {
          modalDistrictCtrl.setValue(district);
        }
      }

      syncModalAddress();
      return displayStreet;
    };
  }

  let modalDistrictCtrl;
  const modalCityCtrl = setupModalDropdown('modal-city', 'modal-city-dropdown', Object.keys(taiwanRegions), selectedCity => {
    const distInput = document.getElementById('modal-district');
    if (selectedCity && taiwanRegions[selectedCity]) {
      distInput.disabled = false;
      distInput.placeholder = '請搜尋或選擇行政區';
      modalDistrictCtrl.setOptions(taiwanRegions[selectedCity]);
    } else {
      distInput.disabled = true;
      distInput.placeholder = '請先選擇縣市';
      modalDistrictCtrl.setOptions([]);
    }
    syncModalAddress();
  });

  modalDistrictCtrl = setupModalDropdown('modal-district', 'modal-district-dropdown', [], () => syncModalAddress());
  document.getElementById('modal-street')?.addEventListener('input', syncModalAddress);
});
