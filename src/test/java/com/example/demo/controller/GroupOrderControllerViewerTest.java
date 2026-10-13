package com.example.demo.controller;

import com.example.demo.service.GroupOrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.mockito.Mockito.verify;

/**
 * 透過 token 取得揪團時，控制器必須把「目前登入者」交給 Service 判斷是否為團員
 * （判斷規則見 GroupOrderServiceAuthorizationTest）。這裡只守住接線：
 * 漏傳 viewerId 時團員會被當成非成員、看不到團內資料；查詢參數的 userId 一律不採信。
 */
@ExtendWith(MockitoExtension.class)
class GroupOrderControllerViewerTest {

    @Mock private GroupOrderService groupOrderService;
    @InjectMocks private GroupOrderController controller;

    @Test
    void viewerIsTakenFromTheAuthenticatedRequestAttributeNotFromAParameter() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("currentUserId", 5L);
        request.setParameter("userId", "99");

        controller.getByToken(request, "TOKEN123");

        verify(groupOrderService).getGroupOrderDTOByToken("TOKEN123", 5L);
    }

    @Test
    void anonymousRequestLooksUpWithoutAViewer() {
        controller.getByToken(new MockHttpServletRequest(), "TOKEN123");

        verify(groupOrderService).getGroupOrderDTOByToken("TOKEN123", null);
    }
}
