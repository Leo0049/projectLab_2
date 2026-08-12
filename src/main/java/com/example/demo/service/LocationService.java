package com.example.demo.service;

import com.example.demo.exception.CustomException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class LocationService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Value("${location.geocoding.base-url:https://nominatim.openstreetmap.org}")
    private String geocodingBaseUrl;

    @Value("${location.geocoding.user-agent:join-drink/1.0}")
    private String geocodingUserAgent;

    public List<Map<String, String>> getCities() {
        List<Map<String, String>> list = new ArrayList<>();
        String[][] cities = {
            {"TPE", "Taipei City"}, {"NTP", "New Taipei City"}, {"TYC", "Taoyuan City"}, {"TXG", "Taichung City"},
            {"TNN", "Tainan City"}, {"KHH", "Kaohsiung City"}, {"KLU", "Keelung City"}, {"HSC", "Hsinchu City"},
            {"HSH", "Hsinchu County"}, {"MIL", "Miaoli County"}, {"CHW", "Changhua County"}, {"NTO", "Nantou County"},
            {"YLH", "Yunlin County"}, {"CHY", "Chiayi City"}, {"CYH", "Chiayi County"}, {"PIF", "Pingtung County"},
            {"ILA", "Yilan County"}, {"HWA", "Hualien County"}, {"TTT", "Taitung County"}, {"PEH", "Penghu County"}
        };
        for (String[] c : cities) {
            Map<String, String> m = new HashMap<>();
            m.put("cityCode", c[0]);
            m.put("cityName", c[1]);
            list.add(m);
        }
        return list;
    }

    public List<Map<String, String>> getDistricts(String cityCode) {
        Map<String, String[]> districts = new HashMap<>();
        districts.put("TXG", new String[]{"Central", "East", "South", "West", "North", "Beitun", "Xitun", "Nantun", "Taiping", "Dali", "Wufeng", "Wuri", "Fengyuan", "Houli", "Shigang", "Dongshi", "Xinshe", "Tanzi", "Daya", "Shengang", "Dadu", "Shalu", "Longjing", "Wuqi", "Qingshui"});
        districts.put("TPE", new String[]{"Zhongzheng", "Datong", "Zhongshan", "Songshan", "Da'an", "Wanhua", "Xinyi", "Shilin", "Beitou", "Neihu", "Nangang", "Wenshan"});
        districts.put("NTP", new String[]{"Banqiao", "Sanchong", "Zhonghe", "Yonghe", "Xinzhuang", "Xindian", "Shulin", "Yingge", "Sanxia", "Tamsui", "Xizhi", "Ruifang", "Tucheng", "Luzhou", "Wugu", "Taishan", "Linkou", "Shenkeng", "Shiding", "Pinglin", "Sanzhi", "Shimen", "Bali", "Pingxi", "Shuangxi", "Gongliao", "Jinshan", "Wanli", "Wulai"});
        districts.put("KHH", new String[]{"Xinxing", "Qianjin", "Lingya", "Yancheng", "Gushan", "Qijin", "Qianzhen", "Sanmin", "Nanzi", "Xiaogang", "Zuoying", "Renwu", "Dashe", "Gangshan", "Luzhu", "Alian", "Tianliao", "Yanchao", "Qiaotou", "Ziguan", "Mituo", "Yong'an", "Hunei", "Fengshan", "Daliao", "Linyuan", "Niaosong", "Dashu", "Qishan", "Meinong", "Liugui", "Neimen", "Shanlin", "Jiaxian", "Taoyuan", "Namaxia", "Maolin", "Qieding"});

        String[] list = districts.getOrDefault(cityCode, new String[]{"District data unavailable"});
        List<Map<String, String>> result = new ArrayList<>();
        for (String d : list) {
            Map<String, String> m = new HashMap<>();
            m.put("districtName", d);
            result.add(m);
        }
        return result;
    }

    public Optional<Coordinates> geocodeAddress(String address) {
        if (address == null || address.trim().isEmpty()) {
            return Optional.empty();
        }

        try {
            String encodedAddress = URLEncoder.encode(address.trim(), StandardCharsets.UTF_8);
            String url = geocodingBaseUrl + "/search?format=jsonv2&limit=1&q=" + encodedAddress;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", geocodingUserAgent)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new CustomException("502", "Geocoding service unavailable");
            }

            List<Map<String, Object>> results = objectMapper.readValue(
                    response.body(),
                    new TypeReference<List<Map<String, Object>>>() {}
            );
            if (results.isEmpty()) {
                return Optional.empty();
            }

            Map<String, Object> first = results.get(0);
            BigDecimal latitude = new BigDecimal(String.valueOf(first.get("lat")));
            BigDecimal longitude = new BigDecimal(String.valueOf(first.get("lon")));
            String displayName = first.get("display_name") != null
                    ? first.get("display_name").toString()
                    : address.trim();
            return Optional.of(new Coordinates(displayName, latitude, longitude));
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            throw new CustomException("502", "Geocoding failed: " + e.getMessage());
        }
    }

    public List<Map<String, Object>> searchAddress(String keyword) {
        return geocodeAddress(keyword).map(coords -> {
            Map<String, Object> m = new HashMap<>();
            m.put("address", coords.getAddress());
            m.put("latitude", coords.getLatitude());
            m.put("longitude", coords.getLongitude());
            return List.of(m);
        }).orElseGet(ArrayList::new);
    }

    public static class Coordinates {
        private final String address;
        private final BigDecimal latitude;
        private final BigDecimal longitude;

        public Coordinates(String address, BigDecimal latitude, BigDecimal longitude) {
            this.address = address;
            this.latitude = latitude;
            this.longitude = longitude;
        }

        public String getAddress() {
            return address;
        }

        public BigDecimal getLatitude() {
            return latitude;
        }

        public BigDecimal getLongitude() {
            return longitude;
        }
    }
}
