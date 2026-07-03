package com.kellen.bean;

import com.baomidou.mybatisplus.extension.ddl.IDdl;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * MyBatis-Plus自动维护DDL。
 * <p>
 * MCP 当前只保留公共基础设施脚本入口。后续新增工具目录、三方系统凭据索引、
 * OAuth token 元数据或调用审计表时，再以新的 `db/mcp-*.sql` 文件追加，
 * 不回改已经执行过的历史脚本。
 */
@Component
public class MysqlDdl implements IDdl {

    /**
     * 当前项目数据源。
     */
    private final DataSource dataSource;

    /**
     * 构造MyBatis-Plus自动维护DDL组件。
     *
     * @param dataSource 当前项目数据源
     * @return void
     * @author sunkailun
     */
    public MysqlDdl(DataSource dataSource) {
        this.dataSource = dataSource; // 保存当前应用数据源，交给MyBatis-Plus DDL运行器执行脚本。
    }

    /**
     * 指定执行脚本的数据源。
     *
     * @param consumer MyBatis-Plus DDL脚本执行器
     * @return void
     * @author sunkailun
     */
    @Override
    public void runScript(Consumer<DataSource> consumer) {
        consumer.accept(dataSource); // 使用当前应用数据源执行DDL脚本，避免业务代码手写建表SQL。
    }

    /**
     * 获取自动维护DDL脚本列表。
     *
     * @return java.util.List<java.lang.String>
     * @author sunkailun
     */
    @Override
    public List<String> getSqlFiles() {
        List<String> sqlFiles = new ArrayList<>(); // 创建DDL脚本列表，保持脚本执行顺序可控。
        return sqlFiles; // 返回 MCP 当前需要自动维护的DDL脚本。
    }
}
