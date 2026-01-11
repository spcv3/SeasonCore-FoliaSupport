package Kinkin.aeternum.calendar;

public final class CalendarState {
    public int year;       // empieza en 1
    public int day;        // 1..daysPerSeason
    public Season season;  // estación actual

    public CalendarState(int year, int day, Season season) {
        this.year = year;
        this.day = day;
        this.season = season;
    }
}
