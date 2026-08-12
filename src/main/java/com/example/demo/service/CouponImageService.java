package com.example.demo.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.example.demo.exception.CustomException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.Map;

@Service
@Slf4j
public class CouponImageService {

    @Autowired
    private Cloudinary cloudinary;

    @Value("${coupon.template-url}")
    private String templateUrl;

    private static final int TEXT_AREA_START = 260;
    private static final int TEXT_AREA_END   = 680;
    private static final int BRAND_Y         = 500;
    private static final int PRODUCT_Y       = 640;
    private static final float BRAND_SIZE    = 85f;
    private static final float PRODUCT_SIZE  = 105f;


    private static final int DRINK_X      = 900;
    private static final int DRINK_Y      = 50;
    private static final int DRINK_WIDTH  = 620;
    private static final int DRINK_HEIGHT = 830;

    public String generateCouponImage(String brandName, String productName) {
        try {
            log.info("載入優惠券底圖：{}", templateUrl);
            BufferedImage template = ImageIO.read(new URL(templateUrl));

            Font notoFont;
            try {
                ClassPathResource fontResource = new ClassPathResource("fonts/NotoSansTC-Bold.ttf");
                try (InputStream is = fontResource.getInputStream()) {
                    notoFont = Font.createFont(Font.TRUETYPE_FONT, is);
                }
            } catch (Exception e) {
                log.warn("找不到 NotoSansTC-Bold.ttf，使用系統預設字型");
                notoFont = new Font("SansSerif", Font.BOLD, 12);
            }

            BufferedImage canvas = new BufferedImage(template.getWidth(), template.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = canvas.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            g.drawImage(template, 0, 0, null);

            // 品牌名稱（深棕色，置中）
            Font brandFont = notoFont.deriveFont(Font.BOLD, BRAND_SIZE);
            g.setFont(brandFont);
            g.setColor(new Color(179, 77, 1, 199));
            FontMetrics brandFm = g.getFontMetrics();
            int brandTextWidth = brandFm.stringWidth(brandName);
            int brandX = TEXT_AREA_START + (TEXT_AREA_END - TEXT_AREA_START - brandTextWidth) / 2;
            g.drawString(brandName, brandX, BRAND_Y);

            // 分類名稱（深黑色，逐字繪製增加字間距，置中）
            Font productFont = notoFont.deriveFont(Font.BOLD, PRODUCT_SIZE);
            g.setFont(productFont);
            g.setColor(new Color(122, 57, 8, 247));
            FontMetrics productFm = g.getFontMetrics();

            int letterSpacing = 8;
            int totalWidth = 0;
            for (char c : productName.toCharArray()) {
                totalWidth += productFm.charWidth(c) + letterSpacing;
            }
            totalWidth -= letterSpacing;

            int productX = TEXT_AREA_START + (TEXT_AREA_END - TEXT_AREA_START - totalWidth) / 2;
            for (char c : productName.toCharArray()) {
                g.drawString(String.valueOf(c), productX, PRODUCT_Y);
                productX += productFm.charWidth(c) + letterSpacing;
            }

            g.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(canvas, "png", baos);

            Map<?, ?> uploadResult = cloudinary.uploader().upload(baos.toByteArray(),
                    ObjectUtils.asMap("folder", "coupons", "resource_type", "image"));

            String finalUrl = (String) uploadResult.get("secure_url");
            log.info("優惠券合成完成：{}", finalUrl);
            return finalUrl;

        } catch (Exception e) {
            log.error("優惠券圖片合成失敗", e);
            throw new CustomException("500", "優惠券圖片合成失敗：" + e.getMessage());
        }
    }
}