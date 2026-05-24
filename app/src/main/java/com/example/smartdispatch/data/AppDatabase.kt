package com.example.smartdispatch.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.smartdispatch.data.dao.*
import com.example.smartdispatch.data.entity.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [Person::class, SkillScore::class, Product::class, ProductProcess::class, Assignment::class, FixedCell::class],
    version = 14,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun personDao(): PersonDao
    abstract fun skillScoreDao(): SkillScoreDao
    abstract fun productDao(): ProductDao
    abstract fun productProcessDao(): ProductProcessDao
    abstract fun assignmentDao(): AssignmentDao
    abstract fun fixedCellDao(): FixedCellDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // version 10 → 11: skill_scores 添加 sortOrder 列，并为已有数据设置顺序
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE skill_scores ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
                // 为已有数据按工序首次出现的id顺序设置sortOrder
                // 获取所有不重复的工序名（按首次出现的id排序）
                val cursor = db.query("SELECT DISTINCT processName FROM skill_scores ORDER BY MIN(id)")
                var order = 0
                while (cursor.moveToNext()) {
                    val processName = cursor.getString(0)
                    db.execSQL("UPDATE skill_scores SET sortOrder = ? WHERE processName = ?", arrayOf(order, processName))
                    order++
                }
                cursor.close()
            }
        }

        // version 11 → 12: persons 添加 jobType 列
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE persons ADD COLUMN jobType TEXT NOT NULL DEFAULT ''")
            }
        }

        // version 12 → 13: 修复已有工序的sortOrder（按首次出现的id排序）
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 获取所有不重复的工序名（按首次出现的id排序）
                val cursor = db.query("SELECT DISTINCT processName FROM skill_scores ORDER BY MIN(id)")
                var order = 0
                while (cursor.moveToNext()) {
                    val processName = cursor.getString(0)
                    db.execSQL("UPDATE skill_scores SET sortOrder = ? WHERE processName = ?", arrayOf(order, processName))
                    order++
                }
                cursor.close()
            }
        }

        // version 13 → 14: 删除 persons 表的 jobType 列
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // SQLite 不支持直接 DROP COLUMN，需要重建表
                db.execSQL("CREATE TABLE persons_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, employeeId TEXT NOT NULL DEFAULT '', onLeave INTEGER NOT NULL DEFAULT 0, insertOrder INTEGER NOT NULL DEFAULT 0)")
                db.execSQL("INSERT INTO persons_new (id, name, employeeId, onLeave, insertOrder) SELECT id, name, employeeId, onLeave, insertOrder FROM persons")
                db.execSQL("DROP TABLE persons")
                db.execSQL("ALTER TABLE persons_new RENAME TO persons")
            }
        }

        // 预置示例数据的 Callback
        private val prepopulateCallback = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                // 数据库首次创建后，在协程中插入示例数据
                CoroutineScope(Dispatchers.IO).launch {
                    val database = INSTANCE ?: return@launch
                    val personDao = database.personDao()
                    val skillScoreDao = database.skillScoreDao()
                    val productDao = database.productDao()
                    val productProcessDao = database.productProcessDao()

                    // 检查是否已有数据，避免重复插入
                    val personCount = getCount(personDao)
                    if (personCount > 0) return@launch

                    // 插入6个人员（使用大间隔insertOrder，方便中间插入）
                    val persons = listOf(
                        Person(name = "张三", employeeId = "001", insertOrder = 100),
                        Person(name = "李四", employeeId = "002", insertOrder = 200),
                        Person(name = "王五", employeeId = "003", insertOrder = 300),
                        Person(name = "赵六", employeeId = "004", insertOrder = 400),
                        Person(name = "陈七", employeeId = "005", insertOrder = 500),
                        Person(name = "刘八", employeeId = "006", insertOrder = 600)
                    )
                    personDao.insertAll(persons)

                    // 获取插入后的人员（带id）
                    val allPersons = personDao.getAllOnce()
                    if (allPersons.isEmpty()) return@launch

                    // 插入工序评分（4个工序：折边、焊接、打磨、组装）
                    val scoreData = mapOf(
                        "张三" to mapOf("折边" to 5, "焊接" to 9, "打磨" to 3, "组装" to 4),
                        "李四" to mapOf("折边" to 9, "焊接" to 4, "打磨" to 5, "组装" to 3),
                        "王五" to mapOf("折边" to 3, "焊接" to 5, "打磨" to 9, "组装" to 4),
                        "赵六" to mapOf("折边" to 4, "焊接" to 3, "打磨" to 5, "组装" to 9),
                        "陈七" to mapOf("折边" to 7, "焊接" to 6, "打磨" to 7, "组装" to 8),
                        "刘八" to mapOf("折边" to 2, "焊接" to 3, "打磨" to 4, "组装" to 5)
                    )
                    val processOrder = mapOf("折边" to 0, "焊接" to 1, "打磨" to 2, "组装" to 3)
                    val scores = mutableListOf<SkillScore>()
                    for (person in allPersons) {
                        val personScores = scoreData[person.name] ?: continue
                        for ((processName, score) in personScores) {
                            scores.add(SkillScore(
                                personId = person.id,
                                processName = processName,
                                score = score,
                                sortOrder = processOrder[processName] ?: 0
                            ))
                        }
                    }
                    skillScoreDao.insertAll(scores)

                    // 插入2个产品
                    val productA = Product(name = "产品A", capacity = 3000, requiredPeople = 8)
                    val productB = Product(name = "产品B", capacity = 1500, requiredPeople = 5)
                    val idA = productDao.insert(productA)
                    val idB = productDao.insert(productB)

                    // 插入产品工序
                    // 产品A: 折边→焊接→打磨→组装
                    productProcessDao.insertAll(listOf(
                        ProductProcess(productId = idA.toInt(), processName = "折边", sortOrder = 0),
                        ProductProcess(productId = idA.toInt(), processName = "焊接", sortOrder = 1),
                        ProductProcess(productId = idA.toInt(), processName = "打磨", sortOrder = 2),
                        ProductProcess(productId = idA.toInt(), processName = "组装", sortOrder = 3)
                    ))
                    // 产品B: 折边→焊接→组装
                    productProcessDao.insertAll(listOf(
                        ProductProcess(productId = idB.toInt(), processName = "折边", sortOrder = 0),
                        ProductProcess(productId = idB.toInt(), processName = "焊接", sortOrder = 1),
                        ProductProcess(productId = idB.toInt(), processName = "组装", sortOrder = 2)
                    ))
                }
            }
        }

        // 辅助方法：获取人员数量（通过原始SQL查询）
        private suspend fun getCount(personDao: PersonDao): Int {
            // 使用 findByName 无法判断数量，直接用 DAO 的方式
            // 由于 PersonDao 没有 count 方法，通过 getAllOnce 判断
            return try {
                val db = INSTANCE ?: return 0
                val cursor = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM persons")
                cursor.moveToFirst()
                val count = cursor.getInt(0)
                cursor.close()
                count
            } catch (_: Exception) {
                0
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "smart_dispatch.db"
                )
                    .addMigrations(MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14)
                    .addCallback(prepopulateCallback)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
