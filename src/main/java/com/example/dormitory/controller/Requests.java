package com.example.dormitory.controller;

import jakarta.validation.constraints.*;
import java.util.List;

public final class Requests {
    private Requests() {}
    public record Login(@NotBlank @Size(max=50) String username,
                        @NotBlank @Size(max=72) String password, @NotBlank @Pattern(regexp="[0-9]{4}") String captcha) {}
    public record Profile(@NotBlank @Size(max=50) String name,
                          @NotBlank @Pattern(regexp="大一|大二|大三|大四") String grade,
                          @Size(max=50) String className, @NotBlank @Pattern(regexp="男|女") String gender,
                          @NotBlank @Pattern(regexp="1[3-9][0-9]{9}") String phone) {}
    public record StudentInput(@NotBlank @Size(max=50) String name,
                               @NotBlank @Pattern(regexp="[0-9]{4,20}") String studentId,
                               @NotBlank @Pattern(regexp="大一|大二|大三|大四") String grade,
                               @Size(max=50) String className, @NotBlank @Pattern(regexp="男|女") String gender,
                               @NotBlank @Pattern(regexp="1[3-9][0-9]{9}") String phone,
                               @Size(max=80) String dormitoryNumber,
                               @Size(min=8,max=72) String initialPassword) {}
    public record DormitoryInput(@NotBlank @Size(max=80) String dormitoryNumber,
                                 @NotBlank @Size(max=50) String building,
                                 @NotBlank @Pattern(regexp="二人间|四人间|六人间") String roomType,
                                 @NotBlank @Pattern(regexp="男|女") String gender, @Size(max=200) String remark) {}
    public record Floor(@Min(0) @Max(99) int doubleRooms, @Min(0) @Max(99) int fourRooms,
                        @Min(0) @Max(99) int sixRooms) {}
    public record BuildingInput(@NotBlank @Pattern(regexp="[^\\s/\\\\-]{1,40}") String name,
                                 @NotBlank @Pattern(regexp="男|女") String gender,
                                 @NotNull @Size(min=1,max=30) List<@jakarta.validation.Valid Floor> floors,
                                 @NotBlank @Pattern(regexp="asc|desc") String sortMode) {}
    public record Ids(@NotEmpty @Size(max=100) List<@Positive Integer> ids) {}
    public record Status(@NotBlank @Pattern(regexp="待处理|处理中|待验收|已完成") String status) {}
    public record Chat(@NotBlank @Size(max=2000) String message) {}
}
