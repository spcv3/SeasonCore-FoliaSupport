package Kinkin.aeternum.calendar;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class SeasonUpdateEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final SeasonService source;
    private final CalendarState state;
    private final boolean dayAdvanced;

    public SeasonUpdateEvent(SeasonService src, CalendarState st, boolean dayAdvanced) {
        super(false); // ⬅️ SINCRÓNICO (antes true)
        this.source = src;
        this.state = st;
        this.dayAdvanced = dayAdvanced;
    }

    public SeasonService getSource() { return source; }
    public CalendarState getState()  { return state; }
    public boolean isDayAdvanced()   { return dayAdvanced; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
