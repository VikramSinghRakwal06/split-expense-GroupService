package com.splitexpense.group.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Payload for {@code POST /api/v1/groups}.
 *
 * <p>Carries no member list and no owner. The creator is taken from the verified JWT and
 * added as the first {@code OWNER}, so there is deliberately no parameter through which one
 * user could create a group owned by somebody else.
 *
 * @param name        what the group is called
 * @param description optional longer note
 * @param currency    ISO-4217 code every expense in the group will be denominated in;
 *                    defaults to INR when omitted
 */
@Schema(description = "Request to create a new expense-sharing group")
public record CreateGroupRequest(

        @Schema(example = "Goa Trip 2026")
        @NotBlank(message = "Name is required")
        @Size(max = 100, message = "Name must not exceed 100 characters")
        String name,

        @Schema(example = "Flights, hotel and everything in between")
        @Size(max = 500, message = "Description must not exceed 500 characters")
        String description,

        /*
         * Validated by pattern rather than an enum: ISO-4217 has ~180 codes, the service
         * stores whatever it is given in a CHAR(3), and enumerating them here would mean a
         * code change every time a deployment wanted a currency nobody had thought of.
         * Nullable so the entity's own default applies.
         */
        @Schema(example = "INR", description = "ISO-4217 code; defaults to INR")
        @Pattern(regexp = "^[A-Z]{3}$",
                message = "Currency must be a three-letter uppercase ISO-4217 code")
        String currency) {
}
