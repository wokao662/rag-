package com.example.rag.auth;

import com.example.rag.profile.UserRepository;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/** 访问码校验：测试期凭码进入，一个码对应一个用户空间。 */
@Service
public class AccessCodeService {
    private static final int MAX_CODE_LENGTH = 64;

    private final DataSource dataSource;
    private final TransactionTemplate transactions;
    private final AccessCodeRepository accessCodes = new AccessCodeRepository();
    private final UserRepository users = new UserRepository();

    public AccessCodeService(DataSource dataSource, TransactionTemplate transactions) {
        this.dataSource = dataSource;
        this.transactions = transactions;
    }

    /** 校验访问码并返回绑定的用户标识；访问码本身即 externalId，首次使用自动建用户。 */
    public String redeem(String code) {
        String normalized = normalize(code);
        return inTransaction(connection -> {
            if (!accessCodes.isActive(connection, normalized)) {
                throw new UnauthorizedException("访问码无效或已被停用");
            }
            users.findOrCreateByExternalId(connection, normalized);
            accessCodes.touchLastUsed(connection, normalized);
            return normalized;
        });
    }

    /** 未发放任何访问码时不启用拦截（本地开发）；启用后要求访问码与请求的用户标识一致。 */
    public boolean isAllowed(String code, String externalId) {
        return inTransaction(connection -> {
            if (!accessCodes.anyExists(connection)) return true;
            return code != null && code.equals(externalId) && accessCodes.isActive(connection, code);
        });
    }

    private static String normalize(String code) {
        if (code == null || code.isBlank()) {
            throw new UnauthorizedException("访问码不能为空");
        }
        String normalized = code.trim();
        if (normalized.length() > MAX_CODE_LENGTH || !normalized.matches("[A-Za-z0-9._-]+")) {
            throw new UnauthorizedException("访问码格式不正确");
        }
        return normalized;
    }

    private <T> T inTransaction(SqlWork<T> work) {
        return transactions.execute(status -> {
            Connection connection = DataSourceUtils.getConnection(dataSource);
            try {
                return work.execute(connection);
            } catch (SQLException error) {
                throw new DataAccessResourceFailureException("数据库操作失败", error);
            } finally {
                DataSourceUtils.releaseConnection(connection, dataSource);
            }
        });
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T execute(Connection connection) throws SQLException;
    }

    public static final class UnauthorizedException extends RuntimeException {
        public UnauthorizedException(String message) { super(message); }
    }
}
