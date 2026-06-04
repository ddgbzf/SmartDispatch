package com.example.smartdispatch.data

import com.example.smartdispatch.data.dao.*
import com.example.smartdispatch.data.entity.*
import kotlinx.coroutines.flow.Flow
import androidx.room.withTransaction

class DispatchRepository(
    private val db: AppDatabase,
    private val personDao: PersonDao,
    private val skillScoreDao: SkillScoreDao,
    private val productDao: ProductDao,
    private val productProcessDao: ProductProcessDao,
    private val assignmentDao: AssignmentDao,
    private val fixedCellDao: FixedCellDao
) {
    val allPersons: Flow<List<Person>> = personDao.getAll()
    val leavePersons: Flow<List<Person>> = personDao.getOnLeave()
    val availablePersons: Flow<List<Person>> = personDao.getAvailable()
    val allProcessNames: Flow<List<String>> = skillScoreDao.getAllProcessNames()
    val allProducts: Flow<List<Product>> = productDao.getAll()
    val allAssignments: Flow<List<Assignment>> = assignmentDao.getAll()
    val allFixedCells: Flow<List<FixedCell>> = fixedCellDao.getAll()

    suspend fun addPerson(name: String, employeeId: String = "") = personDao.insert(Person(name = name, employeeId = employeeId, insertOrder = System.currentTimeMillis().toInt()))
    suspend fun updatePerson(person: Person) = personDao.update(person)
    suspend fun deletePerson(person: Person) = personDao.delete(person)

    suspend fun insertPersonBefore(beforePerson: Person, name: String, employeeId: String = "") {
        // 方案A：物理位置，beforeIndex及之后的全部+1
        val allPersons = personDao.getAllOnce()
        val beforeIndex = allPersons.indexOfFirst { it.id == beforePerson.id }
        val newInsertOrder = beforePerson.insertOrder  // 占据beforePerson的位置
        
        // 用事务包裹所有更新，避免多次触发Flow刷新
        db.withTransaction {
            for (i in beforeIndex until allPersons.size) {
                val p = allPersons[i]
                personDao.update(p.copy(insertOrder = p.insertOrder + 1))
            }
        }
        
        personDao.insert(Person(name = name, employeeId = employeeId, insertOrder = newInsertOrder))
    }

    suspend fun setSkillScore(personId: Int, processName: String, score: Int, sortOrder: Int? = null) {
        val existing = skillScoreDao.find(personId, processName)
        if (existing != null) {
            skillScoreDao.update(existing.copy(score = score, sortOrder = sortOrder ?: existing.sortOrder))
        } else {
            // 新记录：使用该工序已有的sortOrder，避免影响排序
            val order = sortOrder ?: skillScoreDao.getMinSortOrder(processName) ?: 0
            skillScoreDao.insert(SkillScore(personId = personId, processName = processName, score = score, sortOrder = order))
        }
    }

    suspend fun addProduct(name: String, capacity: Int, requiredPeople: Int): Long {
        return productDao.insert(Product(name = name, capacity = capacity, requiredPeople = requiredPeople))
    }
    suspend fun insertProductBefore(beforeProduct: Product, name: String, capacity: Int, requiredPeople: Int) {
        productDao.insert(Product(name = name, capacity = capacity, requiredPeople = requiredPeople))
    }
    suspend fun updateProduct(product: Product) = productDao.update(product)
    suspend fun deleteProduct(product: Product) = productDao.delete(product)

    suspend fun addProcess(productId: Int, processName: String, sortOrder: Int) {
        productProcessDao.insert(ProductProcess(productId = productId, processName = processName, sortOrder = sortOrder))
    }
    suspend fun updateProcess(process: ProductProcess) = productProcessDao.update(process)
    suspend fun deleteProcess(process: ProductProcess) = productProcessDao.delete(process)
    suspend fun deleteProcessesByProduct(productId: Int) = productProcessDao.deleteByProduct(productId)
    suspend fun getProcesses(productId: Int): Flow<List<ProductProcess>> = productProcessDao.getByProduct(productId)

    suspend fun getPersonById(id: Int): Person? = personDao.findById(id)

    suspend fun getScoresByPerson(personId: Int): List<SkillScore> = skillScoreDao.getByPersonOnce(personId)

    suspend fun getAllScoresOnce(): List<SkillScore> = skillScoreDao.getAllOnce()

    suspend fun getProcessesOnce(productId: Int): List<ProductProcess> = productProcessDao.getByProductOnce(productId)

    suspend fun updateAssignment(assignment: Assignment) = assignmentDao.update(assignment)
    suspend fun deleteDynamicAssignments() = assignmentDao.deleteDynamic()
    suspend fun clearAllAssignments() = assignmentDao.deleteAll()
    suspend fun getFixedAssignments(): List<Assignment> = assignmentDao.getFixedOnce()
    suspend fun insertAssignments(assignments: List<Assignment>) = assignmentDao.insertAll(assignments)

    // 工序评分管理
    suspend fun deleteProcess(processName: String) = skillScoreDao.deleteByProcessName(processName)
    suspend fun renameProcess(oldName: String, newName: String) = skillScoreDao.renameProcess(oldName, newName)
    suspend fun processNameExists(processName: String) = skillScoreDao.processNameExists(processName) > 0
    suspend fun getAllProcessNamesOnce() = skillScoreDao.getAllProcessNamesOnce()
    suspend fun addProcessForAllPersons(processName: String, persons: List<Person>, beforeProcess: String? = null) {
        // 方案A：物理位置，targetIndex及之后的全部+1
        val orders = skillScoreDao.getProcessOrders()
        var newSortOrder = 0
        
        if (beforeProcess != null && beforeProcess.isNotBlank()) {
            val targetIndex = orders.indexOfFirst { it.processName == beforeProcess }
            if (targetIndex >= 0) {
                newSortOrder = orders[targetIndex].sortOrder  // 占据目标位置
                
                // 用事务包裹所有更新，避免多次触发Flow刷新
                db.withTransaction {
                    for (i in targetIndex until orders.size) {
                        val po = orders[i]
                        skillScoreDao.updateProcessSortOrder(po.processName, po.sortOrder + 1)
                    }
                }
            } else {
                // 目标工序不存在，放到最后
                val maxOrder = orders.maxOfOrNull { it.sortOrder } ?: -1
                newSortOrder = maxOrder + 1
            }
        } else {
            // 没有指定位置，放到最后
            val maxOrder = orders.maxOfOrNull { it.sortOrder } ?: -1
            newSortOrder = maxOrder + 1
        }
        for (person in persons) {
            skillScoreDao.insert(SkillScore(personId = person.id, processName = processName, score = 0, sortOrder = newSortOrder))
        }
    }

    // 固定单元格
    suspend fun saveFixedCells(colIndex: Int, cells: List<FixedCell>) {
        fixedCellDao.deleteByColumn(colIndex)
        fixedCellDao.insertAll(cells)
    }
    suspend fun deleteFixedCellsByColumn(colIndex: Int) = fixedCellDao.deleteByColumn(colIndex)
    suspend fun clearAllFixedCells() = fixedCellDao.deleteAll()

    suspend fun clearAll() {
        assignmentDao.deleteAll()
        fixedCellDao.deleteAll()
        productProcessDao.deleteAll()
        skillScoreDao.deleteAll()
        productDao.deleteAll()
        personDao.deleteAll()
    }
}
