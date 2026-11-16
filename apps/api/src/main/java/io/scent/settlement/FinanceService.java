package io.scent.settlement;

import io.scent.common.BusinessException;
import io.scent.settlement.SettlementModels.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FinanceService {
    private final SettlementMapper mapper;
    public FinanceService(SettlementMapper mapper) { this.mapper = mapper; }

    @Transactional(readOnly = true, timeout = 5)
    public Finance daily(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.getYear() < 1900 || to.getYear() > 9998 || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= 93) { throw BusinessException.invalid("请选择1到93日的有效日期区间"); }
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        Instant start = from.atStartOfDay(zone).toInstant(), end = to.plusDays(1).atStartOfDay(zone).toInstant();
        var paid = new HashMap<LocalDate,Total>(); var refunded = new HashMap<LocalDate,Total>();
        mapper.paidDays(start, end).forEach(t -> paid.put(t.date(), t));
        mapper.refundDays(start, end).forEach(t -> refunded.put(t.date(), t));
        var days = new ArrayList<Daily>(); long paidSum = 0, refundSum = 0;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            Total p = paid.getOrDefault(day, new Total(day, 0, 0)), r = refunded.getOrDefault(day, new Total(day, 0, 0));
            days.add(new Daily(day, p.cents(), r.cents(), Math.subtractExact(p.cents(), r.cents()), p.count(), r.count()));
            paidSum = Math.addExact(paidSum, p.cents()); refundSum = Math.addExact(refundSum, r.cents());
        }
        return new Finance(from, to, List.copyOf(days), paidSum, refundSum, Math.subtractExact(paidSum, refundSum));
    }
}
