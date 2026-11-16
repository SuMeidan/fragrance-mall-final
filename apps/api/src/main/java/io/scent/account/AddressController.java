package io.scent.account;

import io.scent.common.ApiResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/addresses")
public class AddressController {
    private final AddressService service;
    public AddressController(AddressService service) { this.service = service; }

    @GetMapping
    ApiResult<List<Address>> list(@AuthenticationPrincipal Actor actor) { return ApiResult.ok(service.list(actor.id())); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiResult<Address> create(@AuthenticationPrincipal Actor actor, @Valid @RequestBody Input body) {
        return ApiResult.created(service.save(actor.id(), null, body));
    }

    @PutMapping("/{id}")
    ApiResult<Address> update(@AuthenticationPrincipal Actor actor, @PathVariable long id, @Valid @RequestBody Input body) {
        return ApiResult.ok(service.save(actor.id(), id, body));
    }

    @DeleteMapping("/{id}")
    ApiResult<Void> delete(@AuthenticationPrincipal Actor actor, @PathVariable long id) {
        service.delete(actor.id(), id); return ApiResult.ok(null);
    }

    public record Input(@NotBlank @Size(max = 32) String receiver,
                        @NotNull @Pattern(regexp = "[0-9+\\- ]{6,20}") String phone,
                        @NotBlank @Size(max = 120) String region,
                        @NotBlank @Size(max = 200) String detail,
                        @NotNull Boolean isDefault) { }
}
