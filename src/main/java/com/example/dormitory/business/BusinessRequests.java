package com.example.dormitory.business;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.List;

public final class BusinessRequests {
    private BusinessRequests() {
    }

    public record RoomState(@NotBlank @Pattern(regexp = "ACTIVE|MAINTENANCE|CLOSED") String state,
                            @NotBlank @Size(max = 500) String reason) {
    }

    public record BedState(@NotBlank @Pattern(regexp = "AVAILABLE|RESERVED|DISABLED") String state,
                           @Positive Long studentId, @Size(max = 40) String until,
                           @NotBlank @Size(max = 500) String reason) {
    }

    public record Application(@NotBlank @Pattern(regexp = "CHECKIN|TRANSFER|CHECKOUT") String kind,
                              @Positive Long bedId, @NotBlank @Size(max = 500) String reason) {
    }

    public record Decision(@NotNull Boolean approve, @Positive Long bedId, @NotBlank @Size(max = 500) String note) {
    }

    public record Note(@NotBlank @Size(max = 1000) String note) {
    }

    public record Move(@Positive long studentId, @Positive Long bedId, @NotBlank @Size(max = 500) String reason) {
    }

    public record Leader(@Positive long studentId, @NotBlank @Size(max = 500) String reason) {
    }

    public record Profile(@Pattern(regexp = "ENROLLED|GRADUATED|SUSPENDED|WITHDRAWN") @NotBlank String lifecycle,
                          @Size(max = 100) String college, @Size(max = 100) String major,
                          @Size(max = 50) String counselor, @NotBlank @Size(max = 500) String reason) {
    }

    public record Password(@NotBlank @Size(max = 72) String oldPassword,
                           @NotBlank @Size(min = 8, max = 72) String newPassword) {
    }

    public record Account(@NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]{3,49}") String username,
                          @NotBlank @Size(max = 50) String realName,
                          @NotBlank @Pattern(regexp = "MANAGER|COUNSELOR|WORKER") String role,
                          @NotBlank @Size(min = 8, max = 72) String initialPassword,
                          @NotNull @Size(max = 100) List<@Positive Integer> buildingIds) {
    }

    public record AccountState(@NotNull Boolean enabled, @NotNull @Size(max = 100) List<@Positive Integer> buildingIds,
                               @NotBlank @Size(max = 500) String reason) {
    }

    public record ResetPassword(@NotBlank @Size(min = 8, max = 72) String newPassword,
                                @NotBlank @Size(max = 500) String reason) {
    }

    public record Announcement(@NotBlank @Size(max = 120) String title, @NotBlank @Size(max = 5000) String content,
                               @NotBlank @Pattern(regexp = "ALL|BUILDING") String audience,
                               @Positive Integer buildingId) {
    }

    public record Stay(@NotBlank @Pattern(regexp = "HOLIDAY|OVERNIGHT_OUT|LATE_RETURN") String kind,
                       @NotBlank @Size(max = 40) String startAt,
                       @NotBlank @Size(max = 40) String endAt, @NotBlank @Size(max = 500) String reason,
                       @Pattern(regexp = "^$|1[3-9][0-9]{9}") String contactPhone) {
    }

    public record Visitor(@Positive long roomId, @Positive long studentId, @NotBlank @Size(max = 50) String visitorName,
                          @NotBlank @Pattern(regexp = "1[3-9][0-9]{9}") String phone,
                          @NotBlank @Size(max = 40) String expectedLeaveAt, @Size(max = 500) String note) {
    }

    public record Inspection(@Positive long roomId, @NotBlank @Pattern(regexp = "HYGIENE|SAFETY") String kind,
                             @Min(0) @Max(100) int score,
                             @NotBlank @Size(max = 2000) String detail, @NotBlank @Size(max = 40) String dueAt) {
    }

    public record Review(@NotNull Boolean approve, @NotBlank @Size(max = 1000) String note,
                         @Min(1) @Max(5) Integer rating) {
    }

    public record Bill(@Positive Long studentId, @Positive Long roomId,
                       @NotBlank @Pattern(regexp = "ACCOMMODATION|WATER|ELECTRIC|DAMAGE") String kind,
                       @NotBlank @Size(max = 40) String period,
                       @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal amount,
                       @NotBlank @Size(max = 40) String dueAt, @NotBlank @Size(max = 500) String description) {
    }

    public record Payment(@NotBlank @Pattern(regexp = "PAYMENT|REFUND") String kind,
                          @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal amount,
                          @NotBlank @Pattern(regexp = "CASH|BANK|WECHAT|ALIPAY|OTHER") String method,
                          @NotBlank @Size(max = 100) String referenceNo, @NotBlank @Size(max = 500) String reason) {
    }

    public record Asset(@Positive long roomId, @NotBlank @Size(max = 80) String code,
                        @NotBlank @Size(max = 100) String name,
                        @NotBlank @Pattern(regexp = "FURNITURE|KEY|REMOTE|OTHER") String category,
                        @Size(max = 500) String description) {
    }

    public record Issue(@Positive long studentId, @NotBlank @Size(max = 500) String note) {
    }

    public record ReturnAsset(@NotBlank @Pattern(regexp = "GOOD|DAMAGED|LOST") String condition,
                              @NotBlank @Size(max = 500) String note) {
    }

    public record AssetState(@NotBlank @Pattern(regexp = "AVAILABLE|REPAIR|LOST|RETIRED") String state,
                             @NotBlank @Size(max = 500) String reason) {
    }

    /** priority 省略或 AUTO 时自动判断；NORMAL/URGENT 为人工指定，note 同时用于审计调整理由。 */
    public record Dispatch(@NotBlank @Size(max = 50) String assignee,
                           @Pattern(regexp = "AUTO|NORMAL|URGENT") String priority,
                           @NotBlank @Size(max = 1000) String note) {
    }

    public record Allocation(@NotEmpty @Size(max = 500) List<@Positive Integer> studentIds,
                             @NotEmpty @Size(max = 100) List<@Positive Integer> buildingIds) {
    }

    public record Settings(@NotNull Boolean checkoutRequiresSettledBills,
                           @NotNull Boolean checkoutRequiresReturnedAssets, @NotNull Boolean allResidentsCanReport) {
    }
}
