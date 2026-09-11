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
    /** 默认角色：只能访问自己的用户空间。 */
    public static final String ROLE_TESTER = "tester";
    /** 审核者：除自己的用户空间外，还具备审核策略的资格。 */
    public static final String ROLE_REVIEWER = "reviewer";

    private final DataSource dataSource;
    private final TransactionTemplate transactions;
    private final AccessCodeRepository accessCodes = new AccessCodeRepository();
    private final UserRepository users = new UserRepository();

    public AccessCodeService(DataSource dataSource, TransactionTemplate transactions) {
        this.dataSource = dataSource;
        this.transactions = transactions;
    }

    /**
     * 校验访问码并返回绑定的用户标识与角色；访问码本身即 externalId，首次使用自动建用户。
     *
     * <p>角色随兑换结果一起返回，而不是让调用方再查一次：前端需要它决定是否展示审核入口。
     */
    public Redemption redeem(String code) {
        String normalized = normalize(code);
        return inTransaction(connection -> {
            // 一次查询同时确认有效性、角色与人名，不再单独调 isActive：三者的 WHERE 条件相同，
            // 分开查就是多一趟数据库往返，而且两次查询之间理论上可能读到不同的行。
            String role = accessCodes.accessOf(connection, normalized)
                    .map(AccessCodeRepository.Access::role)
                    .orElseThrow(() -> new UnauthorizedException("访问码无效或已被停用"));
            users.findOrCreateByExternalId(connection, normalized);
            accessCodes.touchLastUsed(connection, normalized);
            return new Redemption(normalized, role);
        });
    }

    /**
     * 校验审核资格，返回审核者身份。
     *
     * <p>三种失败分开报，因为它们要求调用方做的事不同：
     * <ul>
     *   <li>码为空、格式错、不存在或已停用 → {@link UnauthorizedException}（401，换一个码）；</li>
     *   <li>码有效但角色是 tester → {@link ForbiddenException}（403，这个码没资格）；</li>
     *   <li>码是 reviewer 却没有人名 → {@link ForbiddenException}（403，发码的人得先补 label）。</li>
     * </ul>
     * 第三种已经被 V10 的 {@code access_codes_reviewer_label_check} 挡住，这里再查一次不是
     * 重复保险：{@code reviewed_by} 要写人名，而人名一旦落成 NULL，“谁审的”就永久丢了，
     * 且无法从库里还原。宁可当场拒一次审核，也不要一条没有签字人的审核记录。
     *
     * <p>不走 {@link #isAllowed} 的开发模式放行：审核是跨用户的特权动作，而“表里一张码都没”
     * 只能证明本地还没发码，不能证明请求者有审核资格。这里一律失败关闭。
     *
     * <p>不写 {@code last_used_at}：待审列表会被审核界面反复拉取，每次拉取都写一行会产生
     * 无意义的行锁与表膨胀，而“这个码最后一次用在哪天”对审核审计没有价值（{@code reviewed_at}
     * 才是）。
     */
    public Reviewer requireReviewer(String code) {
        String normalized = normalize(code);
        return inTransaction(connection -> {
            AccessCodeRepository.Access access = accessCodes.accessOf(connection, normalized)
                    .orElseThrow(() -> new UnauthorizedException("访问码无效或已被停用"));
            if (!ROLE_REVIEWER.equals(access.role())) {
                throw new ForbiddenException("该访问码没有审核资格");
            }
            String label = access.label();
            if (label == null || label.isBlank()) {
                throw new ForbiddenException("审核者访问码缺少人名（label），无法记录审核者");
            }
            return new Reviewer(normalized, label.trim());
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

    /**
     * 兑换结果。
     *
     * @param externalId 访问码本身即用户标识
     * @param role       {@link #ROLE_TESTER} 或 {@link #ROLE_REVIEWER}
     */
    public record Redemption(String externalId, String role) {
    }

    /**
     * 审核者身份。
     *
     * @param code  已规范化的访问码
     * @param label 人名，写进 {@code strategies.reviewed_by}；已保证非空非空白
     */
    public record Reviewer(String code, String label) {
    }

    public static final class UnauthorizedException extends RuntimeException {
        public UnauthorizedException(String message) { super(message); }
    }

    /** 身份没问题、资格不够。与 401 分开：401 该重新登录，403 重新登录也没用。 */
    public static final class ForbiddenException extends RuntimeException {
        public ForbiddenException(String message) { super(message); }
    }
}
