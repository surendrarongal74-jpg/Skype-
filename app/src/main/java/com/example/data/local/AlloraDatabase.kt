package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.local.dao.AutonomousTaskDao
import com.example.data.local.dao.BookingRecordDao
import com.example.data.local.dao.BrowserDao
import com.example.data.local.dao.EmailDao
import com.example.data.local.dao.MemoryDao
import com.example.data.local.dao.PermissionPolicyDao
import com.example.data.local.dao.PersonalityProfileDao
import com.example.data.local.dao.ProjectDao
import com.example.data.local.dao.SecurityAuditDao
import com.example.data.local.dao.TravelPhotoDao
import com.example.data.local.dao.UserNotificationDao
import com.example.data.local.dao.UserPreferenceDao
import com.example.data.local.dao.WalletDao
import com.example.data.local.dao.WorkspaceFileDao
import com.example.data.local.dao.WorkspaceNoteDao
import com.example.data.local.entity.AutonomousTaskEntity
import com.example.data.local.entity.BookingRecord
import com.example.data.local.entity.BrowserRecordEntity
import com.example.data.local.entity.BrowserTabEntity
import com.example.data.local.entity.EmailItemEntity
import com.example.data.local.entity.MemoryEntryEntity
import com.example.data.local.entity.PermissionPolicyEntity
import com.example.data.local.entity.PersonalityProfileEntity
import com.example.data.local.entity.ProjectEntity
import com.example.data.local.entity.SecurityAuditLogEntity
import com.example.data.local.entity.TaskStepCheckpointEntity
import com.example.data.local.entity.TravelPhotoRecord
import com.example.data.local.entity.UserNotificationEntity
import com.example.data.local.entity.UserPreference
import com.example.data.local.entity.WalletAccountEntity
import com.example.data.local.entity.WalletTransactionEntity
import com.example.data.local.entity.WorkspaceFileEntity
import com.example.data.local.entity.WorkspaceNoteEntity

@Database(
    entities = [
        BookingRecord::class,
        UserPreference::class,
        TravelPhotoRecord::class,
        AutonomousTaskEntity::class,
        TaskStepCheckpointEntity::class,
        WorkspaceNoteEntity::class,
        WalletAccountEntity::class,
        WalletTransactionEntity::class,
        PermissionPolicyEntity::class,
        EmailItemEntity::class,
        BrowserTabEntity::class,
        BrowserRecordEntity::class,
        WorkspaceFileEntity::class,
        SecurityAuditLogEntity::class,
        UserNotificationEntity::class,
        MemoryEntryEntity::class,
        ProjectEntity::class,
        PersonalityProfileEntity::class
    ],
    version = 8,
    exportSchema = false
)
abstract class AlloraDatabase : RoomDatabase() {
    abstract fun bookingRecordDao(): BookingRecordDao
    abstract fun userPreferenceDao(): UserPreferenceDao
    abstract fun travelPhotoDao(): TravelPhotoDao
    abstract fun autonomousTaskDao(): AutonomousTaskDao
    abstract fun workspaceNoteDao(): WorkspaceNoteDao
    abstract fun walletDao(): WalletDao
    abstract fun permissionPolicyDao(): PermissionPolicyDao
    abstract fun emailDao(): EmailDao
    abstract fun browserDao(): BrowserDao
    abstract fun workspaceFileDao(): WorkspaceFileDao
    abstract fun securityAuditDao(): SecurityAuditDao
    abstract fun userNotificationDao(): UserNotificationDao
    abstract fun memoryDao(): MemoryDao
    abstract fun projectDao(): ProjectDao
    abstract fun personalityProfileDao(): PersonalityProfileDao

    companion object {
        @Volatile
        private var INSTANCE: AlloraDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Drop un-scoped v1 cache table so records from prior sessions cannot leak across accounts
                db.execSQL("DROP TABLE IF EXISTS `booking_records`")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `booking_records` (
                        `id` TEXT NOT NULL,
                        `user_id` TEXT NOT NULL,
                        `reference_code` TEXT NOT NULL,
                        `booking_type` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `provider_source` TEXT NOT NULL,
                        `provider_pnr` TEXT,
                        `origin_or_location` TEXT NOT NULL,
                        `destination` TEXT,
                        `start_timestamp` INTEGER NOT NULL,
                        `end_timestamp` INTEGER,
                        `total_amount_usd` REAL NOT NULL,
                        `currency` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `sha256_audit_hash` TEXT NOT NULL,
                        `metadata_json` TEXT NOT NULL,
                        `is_synced` INTEGER NOT NULL,
                        `last_synced_at` INTEGER NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_booking_records_user_id` ON `booking_records` (`user_id`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_booking_records_user_id_reference_code` ON `booking_records` (`user_id`, `reference_code`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_booking_records_user_id_status` ON `booking_records` (`user_id`, `status`)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `user_preferences` ADD COLUMN `display_name` TEXT NOT NULL DEFAULT 'Traveler'")
                db.execSQL("ALTER TABLE `user_preferences` ADD COLUMN `email` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `user_preferences` ADD COLUMN `travel_style` TEXT NOT NULL DEFAULT 'Luxury & Culture'")
                db.execSQL("ALTER TABLE `user_preferences` ADD COLUMN `budget_tier` TEXT NOT NULL DEFAULT 'Balanced Premium'")
                db.execSQL("ALTER TABLE `user_preferences` ADD COLUMN `dark_glass_theme` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `user_preferences` ADD COLUMN `recent_searches_serialized` TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `travel_photos` (
                        `id` TEXT NOT NULL,
                        `user_id` TEXT NOT NULL,
                        `file_path` TEXT NOT NULL,
                        `caption` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `destination_name` TEXT NOT NULL,
                        `latitude` REAL,
                        `longitude` REAL,
                        `captured_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_travel_photos_user_id` ON `travel_photos` (`user_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_travel_photos_user_id_captured_at` ON `travel_photos` (`user_id`, `captured_at`)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `autonomous_tasks` (
                        `id` TEXT NOT NULL,
                        `user_id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `goal` TEXT NOT NULL,
                        `understanding_summary` TEXT NOT NULL,
                        `assumptions_json` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `active_tool_name` TEXT,
                        `plan_json` TEXT NOT NULL,
                        `memory_json` TEXT NOT NULL,
                        `logs_json` TEXT NOT NULL,
                        `executed_idempotency_keys_json` TEXT NOT NULL,
                        `final_outcome` TEXT NOT NULL,
                        `report_markdown` TEXT NOT NULL,
                        `error_summary` TEXT,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_autonomous_tasks_user_id` ON `autonomous_tasks` (`user_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_autonomous_tasks_user_id_updated_at` ON `autonomous_tasks` (`user_id`, `updated_at`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `task_step_checkpoints` (
                        `id` TEXT NOT NULL,
                        `task_id` TEXT NOT NULL,
                        `user_id` TEXT NOT NULL,
                        `step_id` TEXT NOT NULL,
                        `step_index` INTEGER NOT NULL,
                        `title` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `tool_name` TEXT NOT NULL,
                        `args_json` TEXT NOT NULL,
                        `depends_on_json` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `is_idempotent` INTEGER NOT NULL,
                        `requires_approval` INTEGER NOT NULL,
                        `retry_count` INTEGER NOT NULL,
                        `max_retries` INTEGER NOT NULL,
                        `output_summary` TEXT NOT NULL,
                        `output_json` TEXT NOT NULL,
                        `verification_verdict` TEXT NOT NULL,
                        `error_message` TEXT,
                        `idempotency_key` TEXT NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_step_checkpoints_user_id_task_id` ON `task_step_checkpoints` (`user_id`, `task_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_step_checkpoints_task_id_step_index` ON `task_step_checkpoints` (`task_id`, `step_index`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `workspace_notes` (
                        `id` TEXT NOT NULL,
                        `user_id` TEXT NOT NULL,
                        `task_id` TEXT,
                        `note_type` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `tags_csv` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_workspace_notes_user_id` ON `workspace_notes` (`user_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_workspace_notes_user_id_updated_at` ON `workspace_notes` (`user_id`, `updated_at`)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `wallet_accounts` (
                        `id` TEXT NOT NULL,
                        `user_id` TEXT NOT NULL,
                        `currency` TEXT NOT NULL,
                        `verified_balance_minor` INTEGER NOT NULL,
                        `pending_balance_minor` INTEGER NOT NULL,
                        `per_tx_limit_minor` INTEGER NOT NULL,
                        `daily_limit_minor` INTEGER NOT NULL,
                        `monthly_limit_minor` INTEGER NOT NULL,
                        `daily_spent_minor` INTEGER NOT NULL,
                        `monthly_spent_minor` INTEGER NOT NULL,
                        `emergency_locked` INTEGER NOT NULL,
                        `is_minor_account` INTEGER NOT NULL,
                        `guardian_approved` INTEGER NOT NULL,
                        `guardian_email` TEXT,
                        `provider_name` TEXT NOT NULL,
                        `provider_account_ref` TEXT,
                        `reconciliation_status` TEXT NOT NULL,
                        `reconciliation_sha256` TEXT NOT NULL,
                        `last_reconciled_at_ms` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_wallet_accounts_user_id` ON `wallet_accounts` (`user_id`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `wallet_transactions` (
                        `id` TEXT NOT NULL,
                        `user_id` TEXT NOT NULL,
                        `tx_type` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `idempotency_key` TEXT NOT NULL,
                        `provider_transaction_id` TEXT,
                        `provider_verification_status` TEXT NOT NULL,
                        `merchant` TEXT NOT NULL,
                        `product_title` TEXT NOT NULL,
                        `delivery_or_booking_details` TEXT NOT NULL,
                        `subtotal_minor` INTEGER NOT NULL,
                        `taxes_and_fees_minor` INTEGER NOT NULL,
                        `total_minor` INTEGER NOT NULL,
                        `balance_before_minor` INTEGER NOT NULL,
                        `balance_after_minor` INTEGER NOT NULL,
                        `refunded_minor` INTEGER NOT NULL,
                        `receipt_sha256` TEXT NOT NULL,
                        `owner_authorized` INTEGER NOT NULL,
                        `failure_reason` TEXT,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_wallet_transactions_user_id` ON `wallet_transactions` (`user_id`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_wallet_transactions_user_id_idempotency_key` ON `wallet_transactions` (`user_id`, `idempotency_key`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_wallet_transactions_user_id_created_at` ON `wallet_transactions` (`user_id`, `created_at`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `permission_policies` (
                        `userId` TEXT NOT NULL,
                        `wallet_access` TEXT NOT NULL,
                        `purchase_permission` TEXT NOT NULL,
                        `email_read` TEXT NOT NULL,
                        `email_send` TEXT NOT NULL,
                        `browser_access` TEXT NOT NULL,
                        `website_login` TEXT NOT NULL,
                        `file_access` TEXT NOT NULL,
                        `booking_permission` TEXT NOT NULL,
                        `autonomous_task_permission` TEXT NOT NULL,
                        `max_tx_amount_minor` INTEGER NOT NULL,
                        `kill_switch_active` INTEGER NOT NULL,
                        `revoked_domains_csv` TEXT NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`userId`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_permission_policies_userId` ON `permission_policies` (`userId`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `email_items` (
                        `id` TEXT NOT NULL,
                        `user_id` TEXT NOT NULL,
                        `item_kind` TEXT NOT NULL,
                        `provider_message_id` TEXT,
                        `recipient` TEXT NOT NULL,
                        `sender` TEXT NOT NULL,
                        `subject` TEXT NOT NULL,
                        `snippet` TEXT NOT NULL,
                        `body_text` TEXT NOT NULL,
                        `ai_summary` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `is_important` INTEGER NOT NULL,
                        `labels_csv` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_email_items_user_id` ON `email_items` (`user_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_email_items_user_id_updated_at` ON `email_items` (`user_id`, `updated_at`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `browser_tabs` (
                        `id` TEXT NOT NULL,
                        `user_id` TEXT NOT NULL,
                        `url` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `is_active` INTEGER NOT NULL,
                        `is_automation_active` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_browser_tabs_user_id` ON `browser_tabs` (`user_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_browser_tabs_user_id_updated_at` ON `browser_tabs` (`user_id`, `updated_at`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `browser_records` (
                        `id` TEXT NOT NULL,
                        `user_id` TEXT NOT NULL,
                        `record_type` TEXT NOT NULL,
                        `domain` TEXT NOT NULL,
                        `url` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `permission_mode` TEXT NOT NULL,
                        `file_path` TEXT,
                        `status` TEXT NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_browser_records_user_id` ON `browser_records` (`user_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_browser_records_user_id_record_type_updated_at` ON `browser_records` (`user_id`, `record_type`, `updated_at`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `workspace_files` (
                        `id` TEXT NOT NULL,
                        `user_id` TEXT NOT NULL,
                        `file_name` TEXT NOT NULL,
                        `mime_type` TEXT NOT NULL,
                        `size_bytes` INTEGER NOT NULL,
                        `relative_path` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `sha256` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_workspace_files_user_id` ON `workspace_files` (`user_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_workspace_files_user_id_created_at` ON `workspace_files` (`user_id`, `created_at`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `security_audit_logs` (
                        `id` TEXT NOT NULL,
                        `user_id` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `action` TEXT NOT NULL,
                        `outcome` TEXT NOT NULL,
                        `detail` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_security_audit_logs_user_id` ON `security_audit_logs` (`user_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_security_audit_logs_user_id_created_at` ON `security_audit_logs` (`user_id`, `created_at`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `user_notifications` (
                        `id` TEXT NOT NULL,
                        `user_id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `message` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `action_route` TEXT,
                        `is_unread` INTEGER NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_notifications_user_id` ON `user_notifications` (`user_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_notifications_user_id_created_at` ON `user_notifications` (`user_id`, `created_at`)")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `autonomous_tasks` ADD COLUMN `execution_id` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `autonomous_tasks` ADD COLUMN `project_id` TEXT")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `allora_memories` (
                        `id` TEXT NOT NULL,
                        `user_id` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `user_approved` INTEGER NOT NULL,
                        `retention_days` INTEGER NOT NULL,
                        `expires_at` INTEGER NOT NULL,
                        `usage_count` INTEGER NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_allora_memories_user_id` ON `allora_memories` (`user_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_allora_memories_user_id_category_updated_at` ON `allora_memories` (`user_id`, `category`, `updated_at`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_allora_memories_user_id_expires_at` ON `allora_memories` (`user_id`, `expires_at`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `allora_projects` (
                        `id` TEXT NOT NULL,
                        `user_id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `progress_percent` INTEGER NOT NULL,
                        `linked_task_ids_csv` TEXT NOT NULL,
                        `linked_file_ids_csv` TEXT NOT NULL,
                        `linked_thread_id` TEXT,
                        `tags_csv` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_allora_projects_user_id` ON `allora_projects` (`user_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_allora_projects_user_id_updated_at` ON `allora_projects` (`user_id`, `updated_at`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `personality_profiles` (
                        `user_id` TEXT NOT NULL,
                        `preset_mode` TEXT NOT NULL,
                        `warmth_level` INTEGER NOT NULL,
                        `conciseness_level` INTEGER NOT NULL,
                        `proactivity_level` INTEGER NOT NULL,
                        `explanation_transparency` INTEGER NOT NULL,
                        `voice_consistency_enabled` INTEGER NOT NULL,
                        `custom_signature_note` TEXT NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`user_id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_personality_profiles_user_id` ON `personality_profiles` (`user_id`)")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `email_items` ADD COLUMN `account_email` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `email_items` ADD COLUMN `thread_id` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `email_items` ADD COLUMN `attachments_summary` TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getInstance(context: Context): AlloraDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AlloraDatabase::class.java,
                    "allora_offline_cache.db"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8
                    )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
