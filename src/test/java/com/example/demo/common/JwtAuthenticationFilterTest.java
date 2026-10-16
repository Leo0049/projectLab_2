package com.example.demo.common;

import com.example.demo.entity.Brand;
import com.example.demo.entity.Store;
import com.example.demo.entity.User;
import com.example.demo.repository.BrandRepository;
import com.example.demo.repository.StoreRepository;
import com.example.demo.repository.UserRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JwtAuthenticationFilterTest {
    private static final String TOKEN = "test-token";
    private static final Long ACCOUNT_ID = 42L;

    private String role;
    private int tokenVersion;
    private Optional<User> customer = Optional.empty();
    private Optional<Brand> brand = Optional.empty();
    private Optional<Store> store = Optional.empty();
    private JwtAuthenticationFilter filter;

    private final JwtUtils jwtUtils = new JwtUtils() {
        @Override
        public Long getUserIdFromToken(String token) {
            return ACCOUNT_ID;
        }

        @Override
        public String getRoleFromToken(String token) {
            return role;
        }

        @Override
        public int getTokenVersionFromToken(String token) {
            return tokenVersion;
        }
    };

    @BeforeEach
    void setUp() {
        customer = Optional.empty();
        brand = Optional.empty();
        store = Optional.empty();
        tokenVersion = 0;
        SecurityContextHolder.clearContext();
        filter = new JwtAuthenticationFilter(jwtUtils, repository(UserRepository.class),
                repository(BrandRepository.class), repository(StoreRepository.class));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsDeletedCustomerToken() throws Exception {
        User deletedCustomer = new User();
        deletedCustomer.setIsDeleted(true);
        customer = Optional.of(deletedCustomer);

        runFilter("CUSTOMER");

        assertNull(authentication());
    }

    @Test
    void acceptsActiveCustomerToken() throws Exception {
        User activeCustomer = new User();
        activeCustomer.setIsDeleted(false);
        customer = Optional.of(activeCustomer);

        runFilter("CUSTOMER");

        assertAuthority("CUSTOMER");
    }

    @Test
    void rejectsBrandTokenWhenAccountNoLongerExists() throws Exception {
        runFilter("BRAND");

        assertNull(authentication());
    }

    @Test
    void acceptsBrandTokenWhenAccountExists() throws Exception {
        brand = Optional.of(new Brand());

        runFilter("BRAND");

        assertAuthority("BRAND");
    }

    @Test
    void rejectsStoreTokenWhenAccountNoLongerExists() throws Exception {
        runFilter("STORE");

        assertNull(authentication());
    }

    @Test
    void acceptsStoreTokenWhenAccountExists() throws Exception {
        store = Optional.of(new Store());

        runFilter("STORE");

        assertAuthority("STORE");
    }

    @Test
    void rejectsCustomerTokenIssuedBeforeAPasswordChange() throws Exception {
        User changed = new User();
        changed.setTokenVersion(1);
        customer = Optional.of(changed);
        tokenVersion = 0;

        runFilter("CUSTOMER");

        assertNull(authentication());
    }

    @Test
    void acceptsCustomerTokenIssuedAfterAPasswordChange() throws Exception {
        User changed = new User();
        changed.setTokenVersion(1);
        customer = Optional.of(changed);
        tokenVersion = 1;

        runFilter("CUSTOMER");

        assertAuthority("CUSTOMER");
    }

    @Test
    void rejectsCustomerTokenWhenTheAccountRoleHasChanged() throws Exception {
        User reassigned = new User();
        reassigned.setRole("BRAND");
        customer = Optional.of(reassigned);

        runFilter("CUSTOMER");

        assertNull(authentication());
    }

    @Test
    void rejectsStoreTokenIssuedBeforeAPasswordChange() throws Exception {
        Store changed = new Store();
        changed.setTokenVersion(1);
        store = Optional.of(changed);
        tokenVersion = 0;

        runFilter("STORE");

        assertNull(authentication());
    }

    @Test
    void rejectsUnknownRole() throws Exception {
        runFilter("ADMIN");

        assertNull(authentication());
    }

    private void runFilter(String tokenRole) throws IOException, jakarta.servlet.ServletException {
        role = tokenRole;
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        int[] forwarded = {0};
        FilterChain chain = (ignoredRequest, ignoredResponse) -> forwarded[0]++;

        filter.doFilterInternal(request, response, chain);

        assertEquals(1, forwarded[0]);
    }

    private <T> T repository(Class<T> repositoryType) {
        return repositoryType.cast(Proxy.newProxyInstance(repositoryType.getClassLoader(),
                new Class<?>[] {repositoryType}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> repositoryType.getSimpleName() + " test proxy";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> null;
                        };
                    }
                    return switch (method.getName()) {
                        case "findById" -> repositoryType == UserRepository.class ? customer
                                : repositoryType == BrandRepository.class ? brand : store;
                        default -> defaultValue(method.getReturnType());
                    };
                }));
    }

    private Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) return false;
        if (returnType == long.class) return 0L;
        if (returnType == int.class) return 0;
        return null;
    }

    private Authentication authentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    private void assertAuthority(String expected) {
        assertEquals(expected, authentication().getAuthorities().iterator().next().getAuthority());
    }
}
