package com.yubai.blog.common;

import java.sql.SQLException;
import org.flywaydb.core.api.callback.BaseCallback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;

/**
 * Preserves V55's invalid-but-auditable legacy dish asset rows while V58 backfills unrelated
 * metadata. PostgreSQL rechecks a NOT VALID constraint on UPDATE, so the constraint must be
 * replaced around that one historical migration and restored as NOT VALID afterwards.
 */
public final class LegacyDishAssetMigrationCallback extends BaseCallback {
    private static final String VERSION = "58";
    private static final String CONSTRAINT = "ck_dish_assets_exactly_one_content_source";

    @Override
    public boolean supports(Event event, Context context) {
        return isV58(context)
                && (event == Event.BEFORE_EACH_MIGRATE || event == Event.AFTER_EACH_MIGRATE);
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        var sql = event == Event.BEFORE_EACH_MIGRATE ? dropConstraintSql() : addConstraintSql();
        try (var statement = context.getConnection().createStatement()) {
            statement.execute(sql);
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Unable to preserve V58 dish asset compatibility", exception);
        }
    }

    @Override
    public String getCallbackName() {
        return "legacyDishAssetV58Compatibility";
    }

    private static boolean isV58(Context context) {
        return context != null
                && context.getMigrationInfo() != null
                && context.getMigrationInfo().getVersion() != null
                && VERSION.equals(context.getMigrationInfo().getVersion().getVersion());
    }

    private static String dropConstraintSql() {
        return "alter table dish_assets drop constraint if exists " + CONSTRAINT;
    }

    private static String addConstraintSql() {
        return "alter table dish_assets add constraint "
                + CONSTRAINT
                + " check (num_nonnulls(content, storage_key) = 1) not valid";
    }
}
