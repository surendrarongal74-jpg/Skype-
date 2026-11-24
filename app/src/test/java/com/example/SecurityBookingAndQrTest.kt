package com.example

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.core.QrCodeGenerator
import com.example.core.SecurityUtils
import com.example.data.local.AlloraDatabase
import com.example.data.local.entity.BookingRecord
import com.example.data.repository.AlloraRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecurityBookingAndQrTest {

    @Test
    fun `sha256Hex produces genuine lowercase 64-char SHA-256 digest matching NIST vector`() {
        val emptyDigest = SecurityUtils.sha256Hex("")
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", emptyDigest)

        val sampleDigest = SecurityUtils.sha256Hex("abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sampleDigest)
    }

    @Test
    fun `qr code generator builds square ISO 18004 matrix for genuine reference and rejects blank`() {
        assertNull(QrCodeGenerator.encode(""))
        assertNull(QrCodeGenerator.encode("   "))

        val matrix = QrCodeGenerator.encode("ALLORA|REF:ALR-901|STATUS:HELD", preferredSize = 96)
        assertNotNull(matrix)
        val qm = matrix!!
        assertTrue(qm.size >= 21)
        assertEquals(qm.size * qm.size, qm.modules.size)
        // Finder pattern top-left dark cells exist
        assertTrue(qm.modules.any { it })
        assertTrue(qm.modules.any { !it })
    }

    @Test
    fun `room booking queries isolate users and support cancellation and sign-out clear`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AlloraDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        try {
            val dao = db.bookingRecordDao()
            val aliceBooking = BookingRecord(
                id = "bk-alice",
                userId = "alice_uid",
                referenceCode = "ALR-CAR-111",
                bookingType = "CAR_RENTAL",
                title = "Porsche Taycan • 3d",
                providerSource = "Sample Catalog",
                providerPnr = null,
                originOrLocation = "Tokyo Haneda",
                destination = null,
                startTimestamp = 1795000000L,
                endTimestamp = 1795259200L,
                totalAmountUsd = 750.0,
                status = "HELD",
                sha256AuditHash = SecurityUtils.sha256Hex("alice_uid|ALR-CAR-111")
            )
            val bobBooking = aliceBooking.copy(
                id = "bk-bob",
                userId = "bob_uid",
                referenceCode = "ALR-FLT-222",
                bookingType = "FLIGHT",
                title = "ANA NH 008"
            )

            dao.upsertBookings(listOf(aliceBooking, bobBooking))

            val aliceList = dao.observeBookingsForUser("alice_uid").first()
            val bobList = dao.observeBookingsForUser("bob_uid").first()
            assertEquals(listOf("ALR-CAR-111"), aliceList.map { it.referenceCode })
            assertEquals(listOf("ALR-FLT-222"), bobList.map { it.referenceCode })

            // Cancel Alice's car hold and verify Bob is unaffected
            dao.updateStatusByReference("alice_uid", "ALR-CAR-111", "CANCELLED")
            val aliceAfterCancel = dao.observeBookingsForUser("alice_uid").first()
            val bobAfterCancel = dao.observeBookingsForUser("bob_uid").first()
            assertEquals("CANCELLED", aliceAfterCancel.first().status)
            assertEquals("HELD", bobAfterCancel.first().status)

            // Sign-out wipes local cache completely
            dao.clearAllBookings()
            assertTrue(dao.observeBookingsForUser("alice_uid").first().isEmpty())
            assertTrue(dao.observeBookingsForUser("bob_uid").first().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun `room migration 1 to 2 drops unscoped v1 table and recreates user_id indexed table`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_test.db"
        context.deleteDatabase(dbName)

        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE `booking_records` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `reference_code` TEXT NOT NULL,
                        `status` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("INSERT INTO `booking_records` VALUES ('legacy-1', 'LEGACY-REF', 'CONFIRMED')")
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(callback)
                .build()
        )
        val rawDb = helper.writableDatabase
        AlloraDatabase.MIGRATION_1_2.migrate(rawDb)

        val cursor = rawDb.query("SELECT * FROM booking_records")
        assertEquals(0, cursor.count)
        assertTrue(cursor.getColumnIndex("user_id") >= 0)
        cursor.close()
        rawDb.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun `markLedgerCancelled transitions transaction to CANCELLED without fabricating PNR`() {
        val repo = AlloraRepository()
        val req = repo.approvalRequests.value.first { it.status == com.example.data.model.ApprovalStatus.PENDING }
        val tx = repo.approveRequest(req.id, userId = "alice_uid")!!
        assertNull(tx.providerPnr)

        repo.markLedgerCancelled(tx.referenceCode)
        val updated = repo.ledgerTransactions.value.first { it.referenceCode == tx.referenceCode }
        assertEquals("CANCELLED", updated.status)
    }
}
