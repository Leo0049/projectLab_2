package com.example.demo.common;

import com.example.demo.entity.SpecMaster;
import com.example.demo.entity.ToppingMaster;
import com.example.demo.repository.SpecMasterRepository;
import com.example.demo.repository.ToppingMasterRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 平台預設主檔 — 啟動時若 spec_master / topping_master 為空則自動植入。
 */
@Component
@Order(10) // 必須早於 DemoDataSeeder(@Order(20))：它會讀這裡植入的規格/配料主檔
@Slf4j
public class DataSeeder implements ApplicationRunner {

    @Autowired private SpecMasterRepository specMasterRepository;
    @Autowired private ToppingMasterRepository toppingMasterRepository;

    private static final List<String[]> DEFAULT_SPECS = List.of(
        // type, name
        new String[]{"ICE", "正常冰"},
        new String[]{"ICE", "少冰"},
        new String[]{"ICE", "微冰"},
        new String[]{"ICE", "碎冰"},
        new String[]{"ICE", "去冰"},
        new String[]{"ICE", "溫"},
        new String[]{"ICE", "熱"},
        new String[]{"SWEETNESS", "正常糖"},
        new String[]{"SWEETNESS", "少糖"},
        new String[]{"SWEETNESS", "半糖"},
        new String[]{"SWEETNESS", "微糖"},
        new String[]{"SWEETNESS", "一分糖"},
        new String[]{"SWEETNESS", "無糖"},
        new String[]{"SIZE", "大杯L"},
        new String[]{"SIZE", "中杯M"},
        new String[]{"SIZE", "小杯S"}
    );

    private static final List<String[]> DEFAULT_TOPPINGS = List.of(
        // name, defaultPrice
        new String[]{"珍珠",  "10"},
        new String[]{"波霸",  "10"},
        new String[]{"椰果",  "10"},
        new String[]{"布丁",  "10"},
        new String[]{"仙草",  "10"}
    );

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (specMasterRepository.count() == 0) {
            for (String[] row : DEFAULT_SPECS) {
                SpecMaster s = new SpecMaster();
                s.setType(row[0]);
                s.setName(row[1]);
                specMasterRepository.save(s);
            }
            log.info("✅ spec_master 預設資料植入完成（{}筆）", DEFAULT_SPECS.size());
        }

        if (toppingMasterRepository.count() == 0) {
            for (String[] row : DEFAULT_TOPPINGS) {
                ToppingMaster t = new ToppingMaster();
                t.setName(row[0]);
                t.setDefaultPrice(new BigDecimal(row[1]));
                toppingMasterRepository.save(t);
            }
            log.info("✅ topping_master 預設資料植入完成（{}筆）", DEFAULT_TOPPINGS.size());
        }
    }
}
