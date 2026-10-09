package com.smartparking.admin.audit;

import com.smartparking.common.web.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/audit")
@RequiredArgsConstructor
public class AdminAuditController {

    private final AdminAuditService audit;

    @GetMapping
    public PageResponse<AdminActionDto> list(@RequestParam(required = false) String action,
                                             @RequestParam(required = false) String targetType,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return audit.list(action, targetType, page, size);
    }
}
