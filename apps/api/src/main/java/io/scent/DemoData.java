package io.scent;

import javax.sql.DataSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Fictional catalog only, explicit dev profile, never overwrites an existing catalog. */
@Component
@Profile("dev")
public class DemoData implements ApplicationRunner {
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    public DemoData(DataSource dataSource, JdbcTemplate jdbc) { this.dataSource = dataSource; this.jdbc = jdbc; }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM product", Long.class) != 0) { return; }
        var data = new ResourceDatabasePopulator(new ClassPathResource("db/demo/products.sql"));
        data.setSqlScriptEncoding("UTF-8");
        data.execute(dataSource);
    }
}
