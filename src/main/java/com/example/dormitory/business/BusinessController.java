package com.example.dormitory.business;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;

import java.util.*;

import static com.example.dormitory.business.BusinessStore.*;

@RestController
@RequestMapping("/api/business")
public class BusinessController {
    private final BusinessStore db;
    private final BusinessLists lists;
    private final HousingLedger ledger;
    private final BusinessAccess access;
    private final HousingBusinessService housing;
    private final AccountBusinessService accounts;
    private final CommunityBusinessService community;
    private final FinanceAssetService finance;
    private final WorkOrderService work;
    private final BatchBusinessService batches;
    private final BusinessImages images;

    public BusinessController(BusinessStore db, BusinessAccess access, HousingBusinessService housing, AccountBusinessService accounts,
                              CommunityBusinessService community, FinanceAssetService finance, WorkOrderService work, BatchBusinessService batches, BusinessImages images, BusinessLists lists, HousingLedger ledger) {
        this.db = db;
        this.lists = lists;
        this.ledger = ledger;
        this.access = access;
        this.housing = housing;
        this.accounts = accounts;
        this.community = community;
        this.finance = finance;
        this.work = work;
        this.batches = batches;
        this.images = images;
    }

    private Map<String, Object> ok() {
        return Map.of("success", true);
    }

    @GetMapping("/lists/{resource}")
    public BusinessLists.Page list(@PathVariable String resource,
                                   @RequestParam(defaultValue = "1") int page,
                                   @RequestParam(defaultValue = "20") int size,
                                   @RequestParam(defaultValue = "") String search,
                                   @RequestParam(defaultValue = "") String state,
                                   @RequestParam(defaultValue = "") String building,
                                   @RequestParam(defaultValue = "") String from,
                                   @RequestParam(defaultValue = "") String to) {
        if (resource.equals("beds")) {
            access.roles("ADMIN", "MANAGER", "COUNSELOR", "STUDENT");
            ledger.prepareInventory();
        }
        return lists.page(resource, page, size, search, state, building, from, to);
    }

    @GetMapping("/options/{source}")
    public BusinessLists.Page optionPage(@PathVariable String source,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "50") int size,
            @RequestParam(defaultValue = "") String search, @RequestParam(required = false) List<String> ids,
            @RequestParam(defaultValue = "false") boolean unhoused) {
        return lists.options(source, page, size, search, ids == null ? List.of() : ids, unhoused);
    }

    @GetMapping("/options")
    public Object options(@RequestParam(defaultValue = "students,rooms,buildings,workers") String include) {
        String actor = access.actor();
        var requested = new LinkedHashSet<>(Arrays.asList(include.split(",")));
        invalid(Set.of("students", "rooms", "buildings", "workers", "beds").containsAll(requested), "未知选项类型");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("username", actor);
        if (requested.contains("students")) result.put("students", access.role("WORKER") ? List.of() : housing.students());
        if (requested.contains("rooms")) result.put("rooms", access.rooms());
        if (requested.contains("buildings")) result.put("buildings", access.role("ADMIN") ? db.jdbc.queryForList("SELECT * FROM building ORDER BY name") : access.role("MANAGER") ? db.jdbc.queryForList("SELECT b.* FROM building b JOIN manager_building m ON m.building_id=b.id WHERE m.username=?", actor) : List.of());
        if (requested.contains("workers")) result.put("workers", access.role("ADMIN") || access.role("MANAGER") ? db.jdbc.queryForList("SELECT u.username,u.real_name FROM user u LEFT JOIN account_policy p ON p.username=u.username WHERE u.role='worker' AND COALESCE(p.enabled,1)=1") : List.of());
        if (requested.contains("beds")) result.put("beds", housing.beds());
        return result;
    }

    @GetMapping("/beds")
    public Object beds() {
        return housing.beds();
    }

    @PatchMapping("/rooms/{id}/state")
    public Object roomState(@PathVariable long id, @Valid @RequestBody BusinessRequests.RoomState input) {
        housing.roomState(id, input);
        return ok();
    }

    @PatchMapping("/beds/{id}/state")
    public Object bedState(@PathVariable long id, @Valid @RequestBody BusinessRequests.BedState input) {
        housing.bedState(id, input);
        return ok();
    }

    @PostMapping("/housing/move")
    public Object move(@Valid @RequestBody BusinessRequests.Move input) {
        housing.move(input);
        return ok();
    }

    @GetMapping("/housing/applications")
    public Object applications() {
        return housing.applications();
    }

    @PostMapping("/housing/applications")
    public Object apply(@Valid @RequestBody BusinessRequests.Application input) {
        return Map.of("id", housing.apply(input));
    }

    @PostMapping("/housing/applications/{id}/review")
    public Object decide(@PathVariable long id, @Valid @RequestBody BusinessRequests.Decision input) {
        housing.decide(id, input);
        return ok();
    }

    @PostMapping("/housing/applications/{id}/complete")
    public Object complete(@PathVariable long id) {
        housing.complete(id);
        return ok();
    }

    @PostMapping("/housing/applications/{id}/cancel")
    public Object cancel(@PathVariable long id) {
        housing.cancel(id);
        return ok();
    }

    @GetMapping("/housing/history")
    public Object history() {
        return housing.history();
    }

    @GetMapping("/students")
    public Object students() {
        return housing.students();
    }

    @PatchMapping("/students/{id}/profile")
    public Object profile(@PathVariable long id, @Valid @RequestBody BusinessRequests.Profile input) {
        housing.profile(id, input);
        return ok();
    }

    @PostMapping("/students/{id}/account")
    public Object provisionStudent(@PathVariable long id, @Valid @RequestBody BusinessRequests.ResetPassword input) {
        accounts.provisionStudent(id, input);
        return ok();
    }

    @GetMapping("/leaders")
    public Object leaders() {
        return housing.leaders();
    }

    @PostMapping("/rooms/{id}/leader")
    public Object leader(@PathVariable long id, @Valid @RequestBody BusinessRequests.Leader input) {
        housing.appoint(id, input);
        return ok();
    }

    @GetMapping("/accounts")
    public Object accounts() {
        return accounts.accounts();
    }

    @PostMapping("/accounts")
    public Object account(@Valid @RequestBody BusinessRequests.Account input) {
        accounts.create(input);
        return ok();
    }

    @PatchMapping("/accounts/{username}/state")
    public Object accountState(@PathVariable String username, @Valid @RequestBody BusinessRequests.AccountState input) {
        accounts.state(username, input);
        return ok();
    }

    @PostMapping("/accounts/{username}/reset-password")
    public Object reset(@PathVariable String username, @Valid @RequestBody BusinessRequests.ResetPassword input) {
        accounts.reset(username, input);
        return ok();
    }

    @PutMapping("/account/password")
    public Object password(@Valid @RequestBody BusinessRequests.Password input, HttpServletRequest request) {
        long version=accounts.changePassword(input);
        request.getSession().setAttribute("credentialVersion", version);
        return ok();
    }

    @GetMapping("/audit")
    public Object audit() {
        return accounts.audit();
    }

    @GetMapping("/settings")
    public Object settings() {
        return accounts.settings();
    }

    @PutMapping("/settings")
    public Object settings(@Valid @RequestBody BusinessRequests.Settings input) {
        accounts.settings(input);
        return ok();
    }

    @GetMapping("/announcements")
    public Object announcements() {
        return community.announcements();
    }

    @PostMapping("/announcements")
    public Object announce(@Valid @RequestBody BusinessRequests.Announcement input) {
        return Map.of("id", community.announce(input));
    }

    @PostMapping("/announcements/{id}/read")
    public Object readAnnouncement(@PathVariable long id) {
        community.readAnnouncement(id);
        return ok();
    }

    @GetMapping("/notifications")
    public Object notifications() {
        return community.inbox();
    }

    @PostMapping("/notifications/{id}/read")
    public Object readNotification(@PathVariable long id) {
        community.readNotification(id);
        return ok();
    }

    @GetMapping("/stays")
    public Object stays() {
        return community.stays();
    }

    @PostMapping("/stays")
    public Object stay(@Valid @RequestBody BusinessRequests.Stay input) {
        return Map.of("id", community.stay(input));
    }

    @PostMapping("/stays/{id}/review")
    public Object reviewStay(@PathVariable long id, @Valid @RequestBody BusinessRequests.Decision input) {
        community.reviewStay(id, input);
        return ok();
    }

    @PostMapping("/stays/{id}/return")
    public Object returned(@PathVariable long id) {
        community.returned(id);
        return ok();
    }

    @GetMapping("/visitors")
    public Object visitors() {
        return community.visitors();
    }

    @PostMapping("/visitors")
    public Object visitor(@Valid @RequestBody BusinessRequests.Visitor input) {
        return Map.of("id", community.visit(input));
    }

    @PostMapping("/visitors/{id}/leave")
    public Object leave(@PathVariable long id) {
        community.leave(id);
        return ok();
    }

    @GetMapping("/inspections")
    public Object inspections() {
        return community.inspections();
    }

    @PostMapping("/inspections")
    public Object inspection(@Valid @RequestBody BusinessRequests.Inspection input) {
        return Map.of("id", community.inspect(input));
    }

    @PostMapping("/inspections/{id}/rectify")
    public Object rectify(@PathVariable long id, @Valid @RequestBody BusinessRequests.Note input) {
        community.rectify(id, input);
        return ok();
    }

    @PostMapping("/inspections/{id}/review")
    public Object reviewInspection(@PathVariable long id, @Valid @RequestBody BusinessRequests.Review input) {
        community.reviewInspection(id, input);
        return ok();
    }

    @PostMapping("/inspections/{id}/image")
    public Object inspectionImage(@PathVariable long id, @RequestParam(defaultValue = "false") boolean rectification, @RequestParam MultipartFile image) throws java.io.IOException {
        var current = community.inspection(id);
        if (!rectification) access.staffRoom(number(current.get("room_id")));
        String old = text(current.get(rectification ? "rectify_image_path" : "image_path"));
        String stored = images.save(image);
        try {
            community.inspectionImage(id, stored, rectification);
        } catch (RuntimeException error) {
            images.delete(stored);
            throw error;
        }
        if (!old.isBlank()) try {
            images.delete(old);
        } catch (java.io.IOException failure) {
            org.slf4j.LoggerFactory.getLogger(BusinessController.class).warn("Could not remove superseded inspection image");
        }
        return ok();
    }

    @GetMapping("/inspections/{id}/image")
    public ResponseEntity<byte[]> inspectionImage(@PathVariable long id, @RequestParam(defaultValue = "false") boolean rectification) throws java.io.IOException {
        return images.read(text(community.inspection(id).get(rectification ? "rectify_image_path" : "image_path")));
    }

    @GetMapping("/bills")
    public Object bills() {
        return finance.bills();
    }

    @PostMapping("/bills")
    public Object bill(@Valid @RequestBody BusinessRequests.Bill input) {
        return Map.of("ids", finance.createBill(input));
    }

    @GetMapping("/bills/{id}/payments")
    public Object payments(@PathVariable long id) {
        return finance.payments(id);
    }

    @PostMapping("/bills/{id}/payments")
    public Object payment(@PathVariable long id, @Valid @RequestBody BusinessRequests.Payment input) {
        finance.pay(id, input);
        return ok();
    }

    @PostMapping("/bills/{id}/void")
    public Object voidBill(@PathVariable long id, @Valid @RequestBody BusinessRequests.Note input) {
        finance.voidBill(id, input);
        return ok();
    }

    @GetMapping("/assets")
    public Object assets() {
        return finance.assets();
    }

    @PostMapping("/assets")
    public Object asset(@Valid @RequestBody BusinessRequests.Asset input) {
        return Map.of("id", finance.asset(input));
    }

    @PatchMapping("/assets/{id}/state")
    public Object assetState(@PathVariable long id, @Valid @RequestBody BusinessRequests.AssetState input) {
        finance.assetState(id, input);
        return ok();
    }

    @PostMapping("/assets/{id}/issue")
    public Object issue(@PathVariable long id, @Valid @RequestBody BusinessRequests.Issue input) {
        finance.issue(id, input);
        return ok();
    }

    @GetMapping("/handovers")
    public Object handovers() {
        return finance.handovers();
    }

    @PostMapping("/handovers/{id}/return")
    public Object returnAsset(@PathVariable long id, @Valid @RequestBody BusinessRequests.ReturnAsset input) {
        finance.returnAsset(id, input);
        return ok();
    }

    @PostMapping("/repairs/{id}/dispatch")
    public Object dispatch(@PathVariable long id, @Valid @RequestBody BusinessRequests.Dispatch input) {
        work.dispatch(id, input);
        return ok();
    }

    @GetMapping("/repairs/{id}/events")
    public Object events(@PathVariable long id) {
        return work.events(id);
    }

    @PostMapping("/repairs/{id}/start")
    public Object start(@PathVariable long id, @Valid @RequestBody BusinessRequests.Note input) {
        work.transition(id, "处理中", input.note());
        return ok();
    }

    @PostMapping("/repairs/{id}/finish")
    public Object finish(@PathVariable long id, @Valid @RequestBody BusinessRequests.Note input) {
        work.transition(id, "待验收", input.note());
        return ok();
    }

    @PostMapping("/repairs/{id}/review")
    public Object reviewRepair(@PathVariable long id, @Valid @RequestBody BusinessRequests.Review input) {
        work.accept(id, input);
        return ok();
    }

    @GetMapping("/imports/template")
    public ResponseEntity<byte[]> template() throws java.io.IOException {
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=students-template.xlsx").contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(batches.template());
    }

    @PostMapping("/imports/preview")
    public Object importPreview(@RequestParam MultipartFile file) throws java.io.IOException {
        return batches.importPreview(file);
    }

    @PostMapping("/imports/{id}/confirm")
    public Object importConfirm(@PathVariable String id) {
        batches.importConfirm(id);
        return ok();
    }

    @PostMapping("/allocations/preview")
    public Object allocationPreview(@Valid @RequestBody BusinessRequests.Allocation input) {
        return batches.allocationPreview(input);
    }

    @PostMapping("/allocations/{id}/confirm")
    public Object allocationConfirm(@PathVariable String id) {
        batches.allocationConfirm(id);
        return ok();
    }
}
