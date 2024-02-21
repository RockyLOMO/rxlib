package org.rx.jdbc;

import org.junit.jupiter.api.Test;

public class TestJdbc {
    @Test
    public void jdbcExec() {
        JdbcExecutor d = new JdbcExecutor("jdbc:mysql://", "", "bG1hbG1#");
        JdbcUtil.print(d.executeQuery("select * from emr.t_third_api_record\n" +
                "# where third_order_id = 'A01202402201715030375693'\n" +
                "order by updated_time desc"));
    }
}
