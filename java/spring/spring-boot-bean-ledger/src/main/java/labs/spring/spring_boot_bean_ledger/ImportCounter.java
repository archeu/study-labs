package labs.spring.spring_boot_bean_ledger;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfigurationImportEvent;
import org.springframework.boot.autoconfigure.AutoConfigurationImportListener;

/** 记下第一道筛子（只看 metadata，不加载类）之后还剩哪些自动配置类。 */
public class ImportCounter implements AutoConfigurationImportListener {
    static final List<String> AFTER_FILTER = new ArrayList<>();
    static final List<String> EXCLUDED = new ArrayList<>();

    @Override
    public void onAutoConfigurationImportEvent(AutoConfigurationImportEvent e) {
        AFTER_FILTER.clear(); AFTER_FILTER.addAll(e.getCandidateConfigurations());
        EXCLUDED.clear(); EXCLUDED.addAll(e.getExclusions());
    }
}
