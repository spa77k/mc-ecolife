package dev.spa.ecolife.invite;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** Synchronous observation event. Emitted after the corresponding SQLite commit. No IP data. */
public final class InviteEvent extends Event {
    public enum Kind {
        LINKED,
        BLOCKED,
        COMPLETED,
        CANCELLED
    }

    private static final HandlerList HANDLERS = new HandlerList();
    private final Kind kind;
    private final UUID newcomer, inviter;
    private final double inviterAmount, newcomerAmount;
    private final boolean ipOverride;

    public InviteEvent(Kind kind, InviteStore.Link link) {
        this.kind = kind;
        newcomer = link.newcomer();
        inviter = link.inviter();
        inviterAmount = link.inviterAmount();
        newcomerAmount = link.newcomerAmount();
        ipOverride = link.overrideIp();
    }

    public Kind getKind() {
        return kind;
    }

    public UUID getNewcomerId() {
        return newcomer;
    }

    public UUID getInviterId() {
        return inviter;
    }

    public double getInviterAmount() {
        return inviterAmount;
    }

    public double getNewcomerAmount() {
        return newcomerAmount;
    }

    public boolean isIpOverride() {
        return ipOverride;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
