package com.example.smartdispatch

import com.example.smartdispatch.BuildConfig
import android.app.Application
import android.content.Context
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.geometry.Offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.smartdispatch.data.AppDatabase
import com.example.smartdispatch.data.DispatchRepository
import com.example.smartdispatch.data.UserPreferences
import com.example.smartdispatch.data.entity.*
import com.example.smartdispatch.engine.DispatchEngine
import com.example.smartdispatch.model.DispatchResult
import com.example.smartdispatch.model.ProcessAssignment
import com.example.smartdispatch.ui.theme.智能排工Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

class DispatchApplication : Application() {
    val database by lazy { AppDatabase.getDatabase(this) }
    val repository by lazy { DispatchRepository(
        database,
        database.personDao(), database.skillScoreDao(),
        database.productDao(), database.productProcessDao(), database.assignmentDao(),
        database.fixedCellDao()
    )}
    val userPreferences by lazy { UserPreferences(this) }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = (application as DispatchApplication).repository
    private val userPrefs = (application as DispatchApplication).userPreferences
    private val prefs = application.getSharedPreferences("dispatch_state", Context.MODE_PRIVATE)
    
    val allPersons = repo.allPersons.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val leavePersons = repo.leavePersons.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val availablePersons = repo.availablePersons.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val allProcessNames = repo.allProcessNames.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val allProducts = repo.allProducts.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val allAssignments = repo.allAssignments.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val recentProducts = userPrefs.recentProducts.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val allFixedCells = repo.allFixedCells.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _logs = MutableStateFlow(listOf<String>())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()
    private val _dispatchResult = MutableStateFlow<DispatchResult?>(null)
    val dispatchResult: StateFlow<DispatchResult?> = _dispatchResult.asStateFlow()

    // 标记是否已执行过启动排工
    var hasAutoDispatchedOnLaunch = false

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private fun loadDispatchResult(): DispatchResult? {
        val jsonStr = prefs.getString("last_dispatch_result", null) ?: return null
        return try {
            json.decodeFromString(DispatchResult.serializer(), jsonStr)
        } catch (e: Exception) { null }
    }

    private fun saveDispatchResult(result: DispatchResult?) {
        prefs.edit().apply {
            if (result != null) {
                putString("last_dispatch_result", json.encodeToString(DispatchResult.serializer(), result))
            } else {
                remove("last_dispatch_result")
            }
            apply()
        }
    }
    // 固定列人员缓存（上次排工中被分配到固定列产品的人员）
    private val _fixedPeople = MutableStateFlow<Set<String>>(emptySet())
    // 固定列输入槽位索引集合（哪些输入框被标记为固定列）
    private val _fixedInputSlots = MutableStateFlow(loadFixedInputSlots())
    val fixedInputSlots: StateFlow<Set<Int>> = _fixedInputSlots.asStateFlow()
    // 显示设置：字体大小、行高、列宽
    private val _fontSize = MutableStateFlow(prefs.getFloat("display_fontSize", 11f))
    val fontSize: StateFlow<Float> = _fontSize.asStateFlow()
    private val _rowHeight = MutableStateFlow(prefs.getFloat("display_rowHeight", 36f))
    val rowHeight: StateFlow<Float> = _rowHeight.asStateFlow()
    private val _colWidth = MutableStateFlow(prefs.getFloat("display_colWidth", 80f))
    val colWidth: StateFlow<Float> = _colWidth.asStateFlow()

    fun adjustFontSize(delta: Float) {
        val newVal = (_fontSize.value + delta).coerceIn(8f, 20f)
        _fontSize.value = newVal
        prefs.edit().putFloat("display_fontSize", newVal).apply()
    }
    fun adjustRowHeight(delta: Float) {
        val newVal = (_rowHeight.value + delta).coerceIn(20f, 80f)
        _rowHeight.value = newVal
        prefs.edit().putFloat("display_rowHeight", newVal).apply()
    }
    fun adjustColWidth(delta: Float) {
        val newVal = (_colWidth.value + delta).coerceIn(40f, 200f)
        _colWidth.value = newVal
        prefs.edit().putFloat("display_colWidth", newVal).apply()
    }

    // 固定单元格方法
    fun saveFixedCells(colIndex: Int, cells: List<FixedCell>) {
        viewModelScope.launch { repo.saveFixedCells(colIndex, cells) }
    }
    fun deleteFixedCellsByColumn(colIndex: Int) {
        viewModelScope.launch { repo.deleteFixedCellsByColumn(colIndex) }
    }

    // 工序评分管理
    fun deleteScoreProcess(processName: String) {
        viewModelScope.launch { repo.deleteProcess(processName) }
    }
    fun renameScoreProcess(oldName: String, newName: String) {
        viewModelScope.launch { repo.renameProcess(oldName, newName) }
    }
    fun addScoreProcess(processName: String, beforeProcess: String? = null) {
        viewModelScope.launch {
            val persons = repo.allPersons.first()
            repo.addProcessForAllPersons(processName, persons, beforeProcess)
        }
    }

    private fun loadFixedInputSlots(): Set<Int> {
        val str = prefs.getString("fixed_slots", "") ?: ""
        return if (str.isBlank()) emptySet() else str.split(",").mapNotNull { it.toIntOrNull() }.toSet()
    }
    
    private fun saveFixedInputSlots(slots: Set<Int>) {
        prefs.edit().putString("fixed_slots", slots.joinToString(",")).apply()
    }
    
    fun toggleFixedSlot(index: Int) {
        val current = _fixedInputSlots.value.toMutableSet()
        if (index in current) {
            current.remove(index)
        } else {
            current.add(index)
        }
        _fixedInputSlots.value = current
        saveFixedInputSlots(current)
    }

    fun isInputSlotFixed(index: Int): Boolean = index in _fixedInputSlots.value
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    private val _scoreVersion = MutableStateFlow(0)
    val scoreVersion: StateFlow<Int> = _scoreVersion.asStateFlow()
    private val _processVersion = MutableStateFlow(0)
    val processVersion: StateFlow<Int> = _processVersion.asStateFlow()
    
    // 智能排工页输入框状态（持久化到SharedPreferences）
    private val _inputNames = MutableStateFlow(loadInputNames())
    val inputNames: StateFlow<List<String>> = _inputNames.asStateFlow()
    
    private fun loadInputNames(): List<String> {
        return (1..7).map { i -> prefs.getString("input_$i", "") ?: "" }
    }
    
    private fun saveInputNames(names: List<String>) {
        prefs.edit().apply {
            names.forEachIndexed { i, v -> putString("input_${i+1}", v) }
            apply()
        }
    }
    
    fun updateInputName(index: Int, value: String) {
        val oldList = _inputNames.value
        val newList = oldList.toMutableList().apply { set(index, value) }
        saveInputNames(newList)
        _inputNames.value = newList
        // 名称变更时的固定列取消在排工前检测
    }
    
    // 当前正在编辑的输入框索引
    private val _focusedInputIndex = MutableStateFlow(-1)
    val focusedInputIndex: StateFlow<Int> = _focusedInputIndex.asStateFlow()
    fun setFocusedInput(index: Int) { _focusedInputIndex.value = index }
    fun clearFocus() { _focusedInputIndex.value = -1 }
    
    // 匹配的型号名称列表（用于自动完成，精确匹配优先，然后按匹配位置排序）
    val matchedProducts: StateFlow<List<String>> = combine(_inputNames, _focusedInputIndex, allProducts, recentProducts) { names, focusIndex, products, recent ->
        if (focusIndex < 0 || focusIndex >= names.size) emptyList()
        else {
            val text = names[focusIndex].trim()
            if (text.isEmpty()) {
                // 输入为空时显示最近使用的产品（最多10个）
                recent.take(10)
            } else {
                val matched = products.filter { it.name.contains(text, ignoreCase = true) }.map { it.name }
                // 排序：精确匹配最前 → 以输入开头 → 包含输入 → 最近使用
                matched.sortedWith(compareBy<String> { productName ->
                    when {
                        productName.equals(text, ignoreCase = true) -> 0  // 精确匹配
                        productName.startsWith(text, ignoreCase = true) -> 1  // 以输入开头
                        else -> 2  // 包含输入
                    }
                }.thenBy { productName ->
                    val recentIndex = recent.indexOf(productName)
                    if (recentIndex >= 0) recentIndex else Int.MAX_VALUE
                }).take(10)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(1000), emptyList())
    
    fun selectProduct(index: Int, productName: String) {
        val oldList = _inputNames.value
        val newList = oldList.toMutableList().apply { set(index, productName) }
        saveInputNames(newList)
        _inputNames.value = newList
        _focusedInputIndex.value = -1 // 关闭下拉列表
    }
    
    fun selectProductAndDispatch(index: Int, productName: String) {
        selectProduct(index, productName)
        autoDispatch()
    }

    fun addLog(msg: String) { _logs.update { it + msg } }
    fun clearLogs() { _logs.update { emptyList() } }
    fun addPerson(name: String, employeeId: String = "") = viewModelScope.launch { repo.addPerson(name, employeeId) }
    fun toggleLeave(person: Person) = viewModelScope.launch { 
        repo.updatePerson(person.copy(onLeave = !person.onLeave))
    }
    fun toggleLeaveAndDispatch(person: Person) = viewModelScope.launch { 
        repo.updatePerson(person.copy(onLeave = !person.onLeave))
        autoDispatch()
    }
    fun deletePerson(person: Person) = viewModelScope.launch { repo.deletePerson(person) }

    fun updatePersonInfo(person: Person, name: String, employeeId: String) = viewModelScope.launch {
        repo.updatePerson(person.copy(name = name, employeeId = employeeId))
    }

    fun insertPersonBefore(beforePerson: Person, name: String, employeeId: String = "") = viewModelScope.launch {
        repo.insertPersonBefore(beforePerson, name, employeeId)
    }
    
    // 自动执行排工（当输入框变化时调用）
    fun autoDispatch() {
        viewModelScope.launch {
            // 保留重复的型号名称，支持同一型号多实例（如两条产线）
            val names = _inputNames.value.mapNotNull { name ->
                if (name.isNotBlank()) {
                    allProducts.first().find { it.name.equals(name.trim(), ignoreCase = true) }?.name
                } else {
                    null
                }
            }
            if (names.isNotEmpty()) {
                executeDispatchInternal(names)
            }
        }
    }

    fun setSkillScore(personId: Int, processName: String, score: Int) = viewModelScope.launch {
        repo.setSkillScore(personId, processName, score)
        _scoreVersion.update { it + 1 }
    }

    fun setSkillScoreQuiet(personId: Int, processName: String, score: Int) = viewModelScope.launch {
        repo.setSkillScore(personId, processName, score)
    }

    fun updateProcessName(productId: Int, processId: Int, newName: String) = viewModelScope.launch {
        val existing = repo.getProcessesOnce(productId).find { it.id == processId }
        if (existing != null) {
            repo.updateProcess(existing.copy(processName = newName))
            _processVersion.value++
        }
    }

    fun deleteProcess(process: ProductProcess) = viewModelScope.launch {
        repo.deleteProcess(process)
        _processVersion.value++
    }

    fun addProcessToProduct(productId: Int, processName: String) = viewModelScope.launch {
        // 获取当前最大排序号
        val processes = repo.getProcessesOnce(productId)
        repo.addProcess(productId, processName, processes.size)
        _processVersion.value++
    }

    fun refreshProcessVersion() {
        _processVersion.value++
    }

    // 同名产品多次输入时，用列索引+同名序号生成唯一 key，避免 Map 覆盖
    private fun inputProductKey(slotIndex: Int, name: String): String = "$slotIndex:${name.trim()}"

    private fun resultAssignmentsByInput(result: DispatchResult?, inputNames: List<String>, products: List<com.example.smartdispatch.data.entity.Product>): Map<Int, List<ProcessAssignment>> {
        val r = result ?: return emptyMap()
        val productNameMap = products.associateBy { it.name.trim().lowercase() }
        val buckets = r.assignments.groupBy { if (it.inputIndex >= 0) it.inputIndex else (it.columnIndex - 1) / 2 }
        return inputNames.withIndex().mapNotNull { (index, name) ->
            val expectedName = productNameMap[name.trim().lowercase()]?.name ?: return@mapNotNull null
            val matched = buckets[index].orEmpty().filter { it.productName.substringAfter(":") == expectedName }
            if (matched.isEmpty()) null else index to matched
        }.toMap()
    }

    fun assignmentsByInput(result: DispatchResult?, inputNames: List<String>, products: List<com.example.smartdispatch.data.entity.Product>): Map<Int, List<ProcessAssignment>> {
        return resultAssignmentsByInput(result, inputNames, products)
    }

    fun addProduct(name: String, capacity: Int, requiredPeople: Int) = viewModelScope.launch {
        repo.addProduct(name, capacity, requiredPeople)
    }
    fun insertProductBefore(beforeProduct: Product, name: String, capacity: Int, requiredPeople: Int) = viewModelScope.launch {
        repo.insertProductBefore(beforeProduct, name, capacity, requiredPeople)
    }
    fun updateProduct(product: Product) = viewModelScope.launch { repo.updateProduct(product) }
    fun deleteProduct(product: Product) = viewModelScope.launch { repo.deleteProduct(product) }
    fun toggleProductFixed(product: Product) = viewModelScope.launch { 
        repo.updateProduct(product.copy(isFixed = !product.isFixed))
    }

    fun deleteProcessFromProduct(process: ProductProcess) = viewModelScope.launch { repo.deleteProcess(process) }
    
    private suspend fun executeDispatchInternal(selectedProductNames: List<String>) {
        _isLoading.value = true
        addLog("开始排工...")
        try {
            val engine = DispatchEngine()
            val persons = allPersons.first()
            val allProductsList = allProducts.first()
            val productByName = allProductsList.associateBy { it.name }
            val processNames = allProcessNames.first()
            val peopleNames = persons.map { it.name }
            val leaveNames = persons.filter { it.onLeave }.map { it.name }

            // 按输入框槽位构建唯一产品 key，支持同一型号多列同时排工
            val productMap = mutableMapOf<String, com.example.smartdispatch.model.Product>()
            selectedProductNames.forEachIndexed { slotIndex, name ->
                val product = productByName[name]
                if (product != null) {
                    val key = inputProductKey(slotIndex, name)
                    val processes = repo.getProcessesOnce(product.id)
                    productMap[key] = com.example.smartdispatch.model.Product(
                        "$slotIndex:${name.trim()}", product.capacity, product.requiredPeople,
                        processes.map { it.processName }, product.isFixed
                    )
                }
            }
            addLog("排工产品数: ${productMap.size}")

            // ===== 第一步：检测固定列，从上次排工结果中读取人员 =====
            val fixedSlotSet = _fixedInputSlots.value.toMutableSet()
            val lastResult = _dispatchResult.value
            // 按输入框槽位读取上次产品名（新结果用 inputIndex，旧结果回退到列号）
            fun displayProductName(rawName: String): String = rawName.substringAfter(":")
            val lastProductKeys = lastResult?.assignments
                ?.groupBy { assignment -> if (assignment.inputIndex >= 0) assignment.inputIndex else (assignment.columnIndex - 1) / 2 }
                ?.toSortedMap()
                ?.map { (_, assignments) -> displayProductName(assignments.first().productName) }
                ?: emptyList()
            
            // 检测产品变更：产品变了就取消固定列
            if (lastResult != null && fixedSlotSet.isNotEmpty()) {
                var cancelled = 0
                for (slotIndex in fixedSlotSet.toList()) {
                    if (slotIndex < lastProductKeys.size && slotIndex < selectedProductNames.size) {
                        val lastProduct = lastProductKeys[slotIndex]
                        val currentProduct = selectedProductNames[slotIndex]
                        if (lastProduct != currentProduct) {
                            // 产品变了，取消固定
                            fixedSlotSet.remove(slotIndex)
                            cancelled++
                        }
                    }
                }
                if (cancelled > 0) {
                    // 更新UI和持久化
                    _fixedInputSlots.value = fixedSlotSet.toSet()
                    saveFixedInputSlots(fixedSlotSet.toSet())
                    addLog("固定列: $cancelled 个槽位产品变更，自动取消固定")
                }
            }
            
            // 从上次排工结果中读取固定列的人员
            val fixedColumnPersons = mutableMapOf<String, String>() // key=槽位索引_行号, value=人员名
            if (lastResult != null && fixedSlotSet.isNotEmpty()) {
                for (slotIndex in fixedSlotSet) {
                    if (slotIndex < lastProductKeys.size) {
                        val productName = lastProductKeys[slotIndex]
                        lastResult.assignments
                            .filter { assignment ->
                                assignment.assignedPerson != null &&
                                    ((assignment.inputIndex == slotIndex) ||
                                        (assignment.inputIndex < 0 && displayProductName(assignment.productName) == productName))
                            }
                            .forEach { fixedColumnPersons["${slotIndex}_${it.rowIndex}"] = it.assignedPerson!! }
                    }
                }
                addLog("固定列人员: ${fixedColumnPersons.size}个已读取")
            }

            val scoreMap = mutableMapOf<String, MutableMap<String, Int>>()
            for (person in persons) {
                val scores = repo.getScoresByPerson(person.id)
                val pScores = mutableMapOf<String, Int>()
                for (s in scores) { pScores[s.processName] = s.score }
                scoreMap[person.name] = pScores
            }
            addLog("评分人数: ${scoreMap.size}, 工序优先级数: ${processNames.size}")

            engine.setSkillScoresData(scoreMap)
            // 从固定列人员映射构建固定人员集合（用于从可分配池中排除）
            val fixedPeople = fixedColumnPersons.values.toSet()

            // 固定列槽位索引 → 产品key的映射
            val productKeys = selectedProductNames.mapIndexedNotNull { slotIndex, name ->
                val key = inputProductKey(slotIndex, name)
                if (productMap.containsKey(key)) key else null
            }
            val fixedProductKeys = mutableSetOf<String>()
            for (slotIndex in fixedSlotSet) {
                if (slotIndex < productKeys.size) {
                    fixedProductKeys.add(productKeys[slotIndex])
                }
            }
            // 标记固定列产品的 isFixed
            for (key in fixedProductKeys) {
                productMap[key] = productMap[key]!!.copy(isFixed = true)
            }
            addLog("固定列槽位: ${fixedSlotSet.joinToString(", ")}, 固定产品: ${fixedProductKeys.joinToString(", ")}")

            // 读取固定单元格
            val fixedCellList = allFixedCells.value
            val fixedCellMap = fixedCellList.associate { Pair(it.rowIndex, it.colIndex) to it.personName }
            if (fixedCellMap.isNotEmpty()) {
                addLog("固定单元格: ${fixedCellMap.size}条")
            }

            val result = withContext(Dispatchers.IO) {
                engine.runWithData(peopleNames, leaveNames, productMap, processNames, fixedPeople, fixedColumnPersons, fixedSlotSet, fixedCellMap)
            }
            _dispatchResult.value = result
            saveDispatchResult(result)
            // 更新固定列人员集合（用于显示）
            val newFixedPeople = result.assignments
                .filter { assignment -> productKeys.getOrNull(assignment.inputIndex)?.let { productMap[it]?.isFixed == true } == true }
                .mapNotNull { it.assignedPerson }
                .toSet()
            _fixedPeople.value = newFixedPeople
            
            // 更新固定单元格（每次排工后更新为最新人员）
            for (slotIndex in fixedSlotSet) {
                if (slotIndex < productKeys.size) {
                    val productName = productKeys[slotIndex]
                    val colIndex = slotIndex * 2 + 1
                    val cells = result.assignments
                        .filter { it.inputIndex == slotIndex && !it.assignedPerson.isNullOrBlank() }
                        .map { FixedCell(colIndex = colIndex, rowIndex = it.rowIndex, personName = it.assignedPerson!!) }
                    if (cells.isNotEmpty()) {
                        repo.saveFixedCells(colIndex, cells)
                    }
                }
            }
            
            addLog("✅ 排工完成！分配${result.assignedCount}人, 固定列${newFixedPeople.size}人, ${result.statusMessage}")
            // 保存最近使用的产品
            selectedProductNames.forEach { name ->
                userPrefs.addRecentProduct(name)
            }
        } catch (e: Exception) { addLog("❌ 错误: ${e.message}") }
        _isLoading.value = false
    }

    fun executeDispatch(selectedProductNames: List<String> = emptyList()) = viewModelScope.launch {
        executeDispatchInternal(selectedProductNames)
    }

    fun importFromExcel(uri: Uri) = viewModelScope.launch {
        _isLoading.value = true
        addLog("开始导入Excel...")
        try {
            val ctx = getApplication<Application>()
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                val engine = DispatchEngine()
                val (success, error) = withContext(Dispatchers.IO) { engine.loadFromExcel(input) }
                if (success) {
                    val data = engine.getParsedData()
                    val r = (ctx as DispatchApplication).repository
                    withContext(Dispatchers.IO) {
                        r.clearAll()
                        for ((name, employeeId) in data.peopleWithIds) { r.addPerson(name, employeeId) }
                        val allP = r.allPersons.first()
                        for (person in allP) { if (person.name in data.leaveList) { r.updatePerson(person.copy(onLeave = true)) } }
                        val updatedP = r.allPersons.first()
                        // 构建工序名到sortOrder的映射
                        val processOrderMap = data.processNames.withIndex().associate { it.value to it.index }
                        for (person in updatedP) {
                            val scores = data.skillScores[person.name] ?: continue
                            for ((processName, score) in scores) {
                                val order = processOrderMap[processName] ?: 0
                                r.setSkillScore(person.id, processName, score, order)
                            }
                        }
                        for ((name, product) in data.products) {
                            val pid = r.addProduct(name, product.capacity, product.requiredPeople)
                            for ((offset, processName) in product.processes.withIndex()) { r.addProcess(pid.toInt(), processName, offset) }
                        }
                    }
                    addLog("✅ 导入成功: ${data.people.size}人, ${data.products.size}个产品")
                    Toast.makeText(ctx, "导入成功: ${data.people.size}人, ${data.products.size}个产品", Toast.LENGTH_SHORT).show()
                } else { addLog("❌ $error"); Toast.makeText(ctx, error, Toast.LENGTH_LONG).show() }
            }
        } catch (e: Exception) { addLog("❌ ${e.message}") }
        _isLoading.value = false
    }

    fun exportToExcel(uri: Uri) = viewModelScope.launch {
        _isLoading.value = true
        addLog("开始导出Excel...")
        val ctx = getApplication<Application>()
        try {
            val r = (ctx as DispatchApplication).repository
            ctx.contentResolver.openOutputStream(uri)?.use { output ->
                withContext(Dispatchers.IO) {
                    val workbook = org.apache.poi.xssf.usermodel.XSSFWorkbook()
                    
                    // 1. 工序评分表
                    val skillSheet = workbook.createSheet("工序评分")
                    val persons = r.allPersons.first()
                    val processNames = r.allProcessNames.first()  // 保持原始顺序（按id排序）
                    
                    // 表头: 姓名, 工号, 工序1, 工序2, ...
                    val skillHeader = skillSheet.createRow(0)
                    skillHeader.createCell(0).setCellValue("姓名")
                    skillHeader.createCell(1).setCellValue("工号")
                    processNames.forEachIndexed { index, name ->
                        skillHeader.createCell(index + 2).setCellValue(name)
                    }
                    
                    // 数据行
                    persons.forEachIndexed { rowIndex, person ->
                        val row = skillSheet.createRow(rowIndex + 1)
                        row.createCell(0).setCellValue(person.name)
                        row.createCell(1).setCellValue(person.employeeId)
                        val scores = r.getScoresByPerson(person.id)
                        val scoreMap = scores.associate { it.processName to it.score }
                        processNames.forEachIndexed { colIndex, processName ->
                            row.createCell(colIndex + 2).setCellValue(scoreMap[processName]?.toDouble() ?: 0.0)
                        }
                    }
                    
                    // 2. 工序流程表
                    val processSheet = workbook.createSheet("工序流程")
                    val products = r.allProducts.first()
                    
                    // 表头: 产品, 产能, 人数, 工序1, 工序2, ...
                    val processHeader = processSheet.createRow(0)
                    processHeader.createCell(0).setCellValue("产品")
                    processHeader.createCell(1).setCellValue("产能")
                    processHeader.createCell(2).setCellValue("人数")
                    val maxProcessCount = products.maxOfOrNull { r.getProcessesOnce(it.id).size } ?: 0
                    (0 until maxProcessCount.coerceAtLeast(22)).forEach { processHeader.createCell(3 + it).setCellValue("工序${it + 1}") }
                    
                    // 数据行
                    products.forEachIndexed { rowIndex, product ->
                        val row = processSheet.createRow(rowIndex + 1)
                        row.createCell(0).setCellValue(product.name)
                        row.createCell(1).setCellValue(product.capacity.toDouble())
                        row.createCell(2).setCellValue(product.requiredPeople.toDouble())
                        val processes = r.getProcessesOnce(product.id).sortedBy { it.sortOrder }
                        processes.forEachIndexed { colIndex, process ->
                            row.createCell(3 + colIndex).setCellValue(process.processName)
                        }
                    }
                    
                    // 3. 智能排工主表
                    val mainSheet = workbook.createSheet("智能排工")
                    
                    val result = _dispatchResult.value
                    if (result != null) {
                        // 按产品分组收集数据
                        val productData = mutableMapOf<String, MutableList<Pair<String, String>>>() // productName -> [(person, process)]
                        for (a in result.assignments) {
                            if (a.assignedPerson != null) {
                                productData.getOrPut(a.productName) { mutableListOf() }
                                    .add(Pair(a.assignedPerson, a.processName))
                            }
                        }
                        
                        // 优先用products表的产品列表，如果为空则从排工结果提取
                        val sortedProducts = if (products.isNotEmpty()) {
                            products.sortedBy { it.name }.map { it.name }
                        } else {
                            productData.keys.sorted()
                        }
                        val maxDataRows = productData.values.maxOfOrNull { it.size } ?: 0
                        
                        // 第1行：请假人员 + 产品名（每个产品跨2列）
                        val row1 = mainSheet.createRow(0)
                        row1.createCell(0).setCellValue("请假人员")
                        sortedProducts.forEachIndexed { idx, productName ->
                            val startCol = idx * 2 + 1
                            row1.createCell(startCol).setCellValue(productName)
                        }
                        
                        // 第2行：产能、人数
                        val row2 = mainSheet.createRow(1)
                        sortedProducts.forEachIndexed { idx, productName ->
                            val startCol = idx * 2 + 1
                            val product = products.find { it.name == productName }
                            row2.createCell(startCol).setCellValue(product?.capacity?.toString() ?: "")
                            row2.createCell(startCol + 1).setCellValue(product?.requiredPeople?.toString() ?: "")
                        }
                        
                        // 第3行起：工序、人员
                        val leavePersons = persons.filter { it.onLeave }
                        val totalRows = maxOf(leavePersons.size, maxDataRows)
                        
                        for (i in 0 until totalRows) {
                            val dataRow = mainSheet.createRow(i + 2)
                            
                            // A列：请假人员
                            if (i < leavePersons.size) {
                                dataRow.createCell(0).setCellValue(leavePersons[i].name)
                            }
                            
                            // 各产品数据
                            sortedProducts.forEachIndexed { idx, productName ->
                                val startCol = idx * 2 + 1
                                val dataList = productData[productName] ?: emptyList()
                                if (i < dataList.size) {
                                    val (person, process) = dataList[i]
                                    dataRow.createCell(startCol).setCellValue(process)
                                    dataRow.createCell(startCol + 1).setCellValue(person)
                                }
                            }
                        }
                    } else {
                        // 没有排工结果，只显示请假人员
                        val headerRow = mainSheet.createRow(0)
                        headerRow.createCell(0).setCellValue("请假人员")
                        val leavePersons = persons.filter { it.onLeave }
                        leavePersons.forEachIndexed { idx, person ->
                            mainSheet.createRow(idx + 1).createCell(0).setCellValue(person.name)
                        }
                    }
                    
                    workbook.write(output)
                    workbook.close()
                }
                addLog("✅ 导出成功")
                Toast.makeText(ctx, "导出成功", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) { 
            addLog("❌ 导出失败: ${e.message}")
            Toast.makeText(ctx, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
        _isLoading.value = false
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = AndroidColor.rgb(237, 231, 246)
        window.navigationBarColor = AndroidColor.rgb(66, 66, 66)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        setContent {
            智能排工Theme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { MainScreen() }
            }
        }
    }
}

// ========== 设置页面（菜单入口） ==========
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel, onDismiss: () -> Unit) {
    var showProcessEdit by remember { mutableStateOf(false) }
    var showFixedColumn by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    val fontSize by viewModel.fontSize.collectAsState()
    val rowHeight by viewModel.rowHeight.collectAsState()
    val colWidth by viewModel.colWidth.collectAsState()

    if (showProcessEdit) {
        ProcessEditScreen(viewModel = viewModel, onDismiss = { showProcessEdit = false })
    } else if (showFixedColumn) {
        FixedColumnScreen(viewModel = viewModel, onDismiss = { showFixedColumn = false })
    } else if (showHelp) {
        HelpScreen(onDismiss = { showHelp = false })
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("设置", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // 菜单项：使用说明
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { showHelp = true }.padding(vertical = 16.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Help, null, tint = Color(0xFF1976D2), modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("使用说明", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text("查看APP使用帮助", fontSize = 11.sp, color = Color(0xFF999999))
                        }
                        Icon(Icons.Default.KeyboardArrowRight, null, tint = Color(0xFFBDBDBD), modifier = Modifier.size(20.dp))
                    }
                    Divider()
                    // 菜单项：编辑工序流程
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { showProcessEdit = true }.padding(vertical = 16.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Edit, null, tint = Color(0xFF1976D2), modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("编辑工序流程", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text("搜索产品，编辑产能、人数和工序列表", fontSize = 11.sp, color = Color(0xFF999999))
                        }
                        Icon(Icons.Default.KeyboardArrowRight, null, tint = Color(0xFFBDBDBD), modifier = Modifier.size(20.dp))
                    }
                    Divider()
                    // 菜单项：固定列
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { showFixedColumn = true }.padding(vertical = 16.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Star, null, tint = Color(0xFFFBC02D), modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("固定列", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text("设置固定列产品，排工时人员留任原岗位", fontSize = 11.sp, color = Color(0xFF999999))
                        }
                        Icon(Icons.Default.KeyboardArrowRight, null, tint = Color(0xFFBDBDBD), modifier = Modifier.size(20.dp))
                    }
                    Divider()
                    // 菜单项：字体大小
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.TextFields, null, tint = Color(0xFF388E3C), modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("字体大小: ${fontSize.toInt()}sp", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text("调整排工表格的字体大小", fontSize = 11.sp, color = Color(0xFF999999))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                        IconButton(onClick = { viewModel.adjustFontSize(-1f) }) { Icon(Icons.Default.Remove, null) }
                        Text("${fontSize.toInt()}", fontSize = 14.sp, modifier = Modifier.width(30.dp), textAlign = TextAlign.Center)
                        IconButton(onClick = { viewModel.adjustFontSize(1f) }) { Icon(Icons.Default.Add, null) }
                    }
                    Divider()
                    // 菜单项：行高
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.VerticalAlignBottom, null, tint = Color(0xFF7B1FA2), modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("行高: ${rowHeight.toInt()}dp", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text("调整排工表格的行高", fontSize = 11.sp, color = Color(0xFF999999))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                        IconButton(onClick = { viewModel.adjustRowHeight(-2f) }) { Icon(Icons.Default.Remove, null) }
                        Text("${rowHeight.toInt()}", fontSize = 14.sp, modifier = Modifier.width(30.dp), textAlign = TextAlign.Center)
                        IconButton(onClick = { viewModel.adjustRowHeight(2f) }) { Icon(Icons.Default.Add, null) }
                    }
                    Divider()
                    // 菜单项：列宽
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ViewColumn, null, tint = Color(0xFFE65100), modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("列宽: ${colWidth.toInt()}dp", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text("调整排工表格的列宽", fontSize = 11.sp, color = Color(0xFF999999))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                        IconButton(onClick = { viewModel.adjustColWidth(-5f) }) { Icon(Icons.Default.Remove, null) }
                        Text("${colWidth.toInt()}", fontSize = 14.sp, modifier = Modifier.width(30.dp), textAlign = TextAlign.Center)
                        IconButton(onClick = { viewModel.adjustColWidth(5f) }) { Icon(Icons.Default.Add, null) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
        )
    }
}

// ========== 使用说明页面 ==========
@Composable
fun HelpScreen(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("使用说明", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            ) {
                Text("快速上手", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color(0xFF1976D2))
                Text("• 点击右上角导入按钮，选择Excel文件导入数据", fontSize = 13.sp)
                Text("• Excel需包含「工序评分」和「工序流程」两个工作表", fontSize = 13.sp)
                Spacer(Modifier.height(16.dp))

                Text("【工序评分】", fontWeight = FontWeight.Bold, color = Color(0xFF1976D2))
                Text("• 显示所有人员对各工序的技能评分", fontSize = 13.sp)
                Text("• 点击评分格可直接编辑评分", fontSize = 13.sp)
                Text("• 长按工序名：编辑/插入/删除工序", fontSize = 13.sp)
                Text("• 长按人员名：编辑/插入/删除人员", fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))

                Text("【工序流程】", fontWeight = FontWeight.Bold, color = Color(0xFF1976D2))
                Text("• 显示所有产品的工序流程（名称、产能、需求人数、工序列表）", fontSize = 13.sp)
                Text("• 长按产品名称：插入/删除产品", fontSize = 13.sp)
                Text("• 工序编辑在设置→编辑工序流程中完成", fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))

                Text("【智能排工】", fontWeight = FontWeight.Bold, color = Color(0xFF1976D2))
                Text("• 输入产品型号名称，系统自动匹配", fontSize = 13.sp)
                Text("• 左侧输入框可输入请假人员", fontSize = 13.sp)
                Text("• 点击排工按钮，系统根据评分自动分配最优人员", fontSize = 13.sp)
                Text("• 底部橙色区域显示未分配人员", fontSize = 13.sp)
                Text("• 顶部统计栏显示总人数、请假、已分配、欠缺人数", fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))

                Text("如需帮助微信:ddg18933639(大胆哥)", fontSize = 13.sp, color = Color(0xFF666666))
                Text("项目源码:https://github.com/ddgbzf/SmartDispatch", fontSize = 13.sp, color = Color(0xFF666666))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } }
    )
}

// ========== 编辑工序流程页面 ==========
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ProcessEditScreen(viewModel: MainViewModel, onDismiss: () -> Unit) {
    val products by viewModel.allProducts.collectAsState()
    val repo = (LocalContext.current.applicationContext as DispatchApplication).repository
    val coroutineScope = rememberCoroutineScope()
    var searchText by remember { mutableStateOf("") }
    var editingProduct by remember { mutableStateOf<Product?>(null) }
    
    // 本地编辑状态（不立即保存）
    var editCapacity by remember { mutableStateOf("") }
    var editPeople by remember { mutableStateOf("") }
    var editingProcesses by remember { mutableStateOf<List<ProductProcess>>(emptyList()) }
    var originalProcesses by remember { mutableStateOf<List<ProductProcess>>(emptyList()) }
    var newProcessName by remember { mutableStateOf("") }
    var nextTempProcessId by remember { mutableIntStateOf(-1) }
    // 拖动状态提升到外层，避免重组时丢失
    var dragFromIndex by remember { mutableStateOf(-1) }
    var dragAccumY by remember { mutableStateOf(0f) }
    
    // 历史记录（用于撤销）
    var history by remember { mutableStateOf<List<Triple<String, String, List<ProductProcess>>>>(emptyList()) }
    
    // 是否有修改
    val hasChanges = remember(editCapacity, editPeople, editingProcesses, originalProcesses, editingProduct) {
        editingProduct != null && (
            editCapacity != editingProduct!!.capacity.toString() ||
            editPeople != editingProduct!!.requiredPeople.toString() ||
            editingProcesses.map { it.processName } != originalProcesses.map { it.processName }
        )
    }

    val filteredProducts = remember(searchText, products) {
        if (searchText.length < 2) emptyList()
        else products.filter { it.name.contains(searchText.trim(), ignoreCase = true) }.take(30)
    }

    val context = LocalContext.current
    LaunchedEffect(editingProduct) {
        if (editingProduct != null) {
            val processes = repo.getProcessesOnce(editingProduct!!.id).sortedBy { it.sortOrder }
            editingProcesses = processes
            originalProcesses = processes.map { it.copy() }
            editCapacity = editingProduct!!.capacity.toString()
            editPeople = editingProduct!!.requiredPeople.toString()
            history = emptyList()  // 清空历史
            nextTempProcessId = -1
            android.widget.Toast.makeText(context, "加载: ${editingProduct!!.name}, ${processes.size}个工序", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    
    fun saveProcessesForDatabase(): List<ProductProcess> {
        return editingProcesses.mapIndexed { index, process ->
            process.copy(
                id = process.id.coerceAtLeast(0),
                sortOrder = index
            )
        }
    }

    // 保存历史
    fun saveHistory() {
        history = history + Triple(editCapacity, editPeople, saveProcessesForDatabase().map { it.copy() })
        if (history.size > 20) history = history.drop(1)  // 最多20步
    }
    
    // 撤销
    fun undo() {
        if (history.isNotEmpty()) {
            val last = history.last()
            history = history.dropLast(1)
            editCapacity = last.first
            editPeople = last.second
            editingProcesses = last.third
        }
    }
    
    // 移动工序（只交换文本内容，不交换列表项，避免id错乱）
    fun moveProcess(from: Int, to: Int) {
        if (from == to || from < 0 || to < 0 || from >= editingProcesses.size || to >= editingProcesses.size) return
        saveHistory()
        val list = editingProcesses.toMutableList()
        val fromName = list[from].processName
        val toName = list[to].processName
        list[from] = list[from].copy(processName = toName)
        list[to] = list[to].copy(processName = fromName)
        editingProcesses = list
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = null,
        text = {
            Column(modifier = Modifier.fillMaxWidth().height(500.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                // 标题行（放在text内部，减少空白）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("编辑工序流程", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (history.isNotEmpty()) {
                        TextButton(onClick = { undo() }) {
                            Icon(Icons.Default.Undo, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("撤销", fontSize = 12.sp)
                        }
                    }
                }
                if (editingProduct != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(32.dp)) {
                        IconButton(onClick = { editingProduct = null }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.ArrowBack, null, modifier = Modifier.size(16.dp)) }
                        Text(editingProduct!!.name, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    // 产能/人数 - 紧凑排列
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(24.dp)) {
                        Text("产能:", fontSize = 10.sp, modifier = Modifier.width(26.dp))
                        BasicTextField(
                            value = editCapacity, onValueChange = { if (it.all { c -> c.isDigit() }) editCapacity = it },
                            singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp, color = Color.Black),
                            modifier = Modifier.width(50.dp).height(20.dp).border(1.dp, Color(0xFFBDBDBD), RoundedCornerShape(4.dp)).padding(horizontal = 3.dp, vertical = 1.dp),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.Black)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("人数:", fontSize = 10.sp, modifier = Modifier.width(26.dp))
                        BasicTextField(
                            value = editPeople, onValueChange = { if (it.all { c -> c.isDigit() }) editPeople = it },
                            singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp, color = Color.Black),
                            modifier = Modifier.width(50.dp).height(20.dp).border(1.dp, Color(0xFFBDBDBD), RoundedCornerShape(4.dp)).padding(horizontal = 3.dp, vertical = 1.dp),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.Black)
                        )
                    }
                    Text("工序列表（长按手柄拖动）:", fontSize = 11.sp, color = Color(0xFF666666))
                    Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            items(editingProcesses.size, key = { index ->
                                editingProcesses[index].id
                            }) { index ->
                                val process = editingProcesses[index]
                                var editName by remember(process.id, process.processName) { mutableStateOf(process.processName) }
                                val isDragging = dragFromIndex == index
                                // 拖动时置顶显示
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .zIndex(if (isDragging) 100f else 1f)
                                        .offset(y = if (isDragging) dragAccumY.dp else 0.dp)
                                        .shadow(
                                            if (isDragging) 8.dp else 0.dp,
                                            RoundedCornerShape(2.dp)
                                        )
                                        .background(
                                            if (isDragging) Color(0xFFE3F2FD) else Color.White,
                                            RoundedCornerShape(2.dp)
                                        )
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth().height(26.dp)
                                    ) {
                                        Text("${index + 1}.", fontSize = 11.sp, color = Color(0xFF666666), modifier = Modifier.width(28.dp), textAlign = TextAlign.Center)
                                        BasicTextField(
                                            value = editName,
                                            onValueChange = {
                                                editName = it
                                                editingProcesses = editingProcesses.toMutableList().also { list ->
                                                    list[index] = process.copy(processName = it)
                                                }
                                            },
                                            singleLine = true,
                                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = Color.Black),
                                            modifier = Modifier.width(160.dp).height(24.dp).border(1.dp, Color(0xFFBDBDBD), RoundedCornerShape(2.dp)).padding(horizontal = 3.dp, vertical = 1.dp),
                                            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.Black),
                                            decorationBox = { innerTextField ->
                                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                                                    innerTextField()
                                                }
                                            }
                                        )
                                        Spacer(Modifier.weight(1f))
                                        IconButton(onClick = {
                                            saveHistory()
                                            editingProcesses = editingProcesses.filterIndexed { i, _ -> i != index }.mapIndexed { i, p -> p.copy(sortOrder = i) }
                                        }, modifier = Modifier.size(26.dp)) {
                                            Icon(Icons.Default.Delete, null, tint = Color(0xFFC62828), modifier = Modifier.size(14.dp))
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        // 拖动手柄 - 长按触发拖动
                                        Box(
                                            modifier = Modifier
                                                .width(32.dp)
                                                .height(24.dp)
                                                .pointerInput(process.id) {
                                                    detectDragGesturesAfterLongPress(
                                                        onDragStart = {
                                                            dragFromIndex = index
                                                            dragAccumY = 0f
                                                        },
                                                        onDragEnd = {
                                                            dragFromIndex = -1
                                                            dragAccumY = 0f
                                                        },
                                                        onDrag = { change: PointerInputChange, dragAmount: Offset ->
                                                            change.consume()
                                                            dragAccumY += dragAmount.y
                                                            val rowHeight = 25f
                                                            val moveSteps = (dragAccumY / rowHeight).toInt()
                                                            if (moveSteps != 0 && dragFromIndex >= 0) {
                                                                val newIndex = (dragFromIndex + moveSteps)
                                                                    .coerceIn(0, editingProcesses.size - 1)
                                                                if (newIndex != dragFromIndex) {
                                                                    moveProcess(dragFromIndex, newIndex)
                                                                    dragAccumY -= moveSteps * rowHeight
                                                                    dragFromIndex = newIndex
                                                                }
                                                            }
                                                        }
                                                    )
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.DragHandle, null, tint = Color(0xFF999999), modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                            // 添加新工序
                            item {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 2.dp).height(26.dp)) {
                                    Text("${editingProcesses.size + 1}.", fontSize = 11.sp, color = Color(0xFF666666), modifier = Modifier.width(28.dp), textAlign = TextAlign.Center)
                                    BasicTextField(
                                        value = newProcessName, onValueChange = { newProcessName = it },
                                        singleLine = true,
                                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = Color.Black),
                                        modifier = Modifier.width(160.dp).height(24.dp).border(1.dp, Color(0xFFBDBDBD), RoundedCornerShape(2.dp)).padding(horizontal = 3.dp, vertical = 1.dp),
                                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.Black),
                                        decorationBox = { innerTextField ->
                                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                                                if (newProcessName.isEmpty()) {
                                                    Text("新工序名称", fontSize = 11.sp, color = Color(0xFFAAAAAA))
                                                }
                                                innerTextField()
                                            }
                                        }
                                    )
                                    Spacer(Modifier.weight(1f))
                                        IconButton(onClick = {
                                            if (newProcessName.isNotBlank() && editingProduct != null) {
                                                saveHistory()
                                                val processName = newProcessName.trim()
                                                val productId = editingProduct!!.id
                                                val sortOrder = editingProcesses.size
                                                newProcessName = ""
                                                coroutineScope.launch {
                                                    repo.addProcess(productId, processName, sortOrder)
                                                    val refreshedProcesses = repo.getProcessesOnce(productId)
                                                    originalProcesses = refreshedProcesses
                                                    editingProcesses = refreshedProcesses
                                                    nextTempProcessId = -1
                                                    viewModel.refreshProcessVersion()
                                                }
                                            }
                                        }, enabled = newProcessName.isNotBlank() && editingProduct != null, modifier = Modifier.size(26.dp)) {
                                        Icon(Icons.Default.Add, null, tint = Color(0xFF1976D2), modifier = Modifier.size(14.dp))
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Spacer(modifier = Modifier.width(32.dp)) // 占位，对齐手柄位置
                                }
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = searchText, onValueChange = { searchText = it },
                        placeholder = { Text("输入型号名称搜索（至少2个字符）") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("共${products.size}个产品，已过滤${filteredProducts.size}个", fontSize = 11.sp, color = Color(0xFF666666))
                    Spacer(Modifier.height(4.dp))
                    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (searchText.length < 2) {
                            item {
                                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                                    Text("请输入至少2个字符搜索", color = Color(0xFF999999))
                                }
                            }
                        } else if (filteredProducts.isEmpty()) {
                            item {
                                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                                    Text("未找到匹配的产品", color = Color(0xFF999999))
                                }
                            }
                        } else {
                            items(filteredProducts) { product ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable { editingProduct = product }.padding(vertical = 6.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(product.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("产能:${product.capacity} 人数:${product.requiredPeople}", fontSize = 11.sp, color = Color(0xFF666666))
                                    }
                                    Icon(Icons.Default.Edit, null, tint = Color(0xFF1976D2), modifier = Modifier.size(18.dp))
                                }
                                Divider()
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                if (editingProduct != null && hasChanges) {
                    TextButton(onClick = {
                        coroutineScope.launch {
                            // 保存产能/人数
                            viewModel.updateProduct(editingProduct!!.copy(
                                capacity = editCapacity.toIntOrNull() ?: 0,
                                requiredPeople = editPeople.toIntOrNull() ?: 0
                            ))
                            // 删除旧工序，插入新工序
                            repo.deleteProcessesByProduct(editingProduct!!.id)
                            saveProcessesForDatabase().forEach { p ->
                                repo.addProcess(editingProduct!!.id, p.processName, p.sortOrder)
                            }
                            viewModel.refreshProcessVersion()
                            val savedProductId = editingProduct!!.id
                            editingProduct = null
                            val refreshedProcesses = repo.getProcessesOnce(savedProductId)
                            originalProcesses = refreshedProcesses
                            editingProcesses = refreshedProcesses
                        }
                    }) {
                        Icon(Icons.Default.Check, null, tint = Color(0xFF2E7D32), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("保存", color = Color(0xFF2E7D32))
                    }
                }
                TextButton(onClick = onDismiss) { Text("返回") }
            }
        }
    )
}

// ========== 固定列设置页面 ==========
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FixedColumnScreen(viewModel: MainViewModel, onDismiss: () -> Unit) {
    val inputNames by viewModel.inputNames.collectAsState()
    val fixedSlots by viewModel.fixedInputSlots.collectAsState()
    val dispatchResult by viewModel.dispatchResult.collectAsState()
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("固定列", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().height(400.dp)) {
                Text("点击 ⭐ 标记固定列，固定列人员排工时留任原岗位", fontSize = 11.sp, color = Color(0xFF666666))
                Spacer(Modifier.height(12.dp))
                LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(inputNames.size) { index ->
                        val name = inputNames[index]
                        val isFixed = index in fixedSlots
                        if (name.isBlank()) {
                            // 空槽位
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("第${index + 1}列", fontSize = 13.sp, color = Color(0xFF999999))
                                Spacer(Modifier.weight(1f))
                                Text("(空)", fontSize = 12.sp, color = Color(0xFFCCCCCC))
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("第${index + 1}列", fontSize = 11.sp, color = Color(0xFF999999))
                                    Text(name, fontSize = 14.sp, fontWeight = if (isFixed) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                IconButton(onClick = {
                                    viewModel.toggleFixedSlot(index)
                                    // 同时处理固定单元格
                                    if (!isFixed) {
                                        // 标记固定：从排工结果读取该列人员，保存到 FixedCell
                                        val result = dispatchResult
                                        if (result != null) {
                                            val colIndex = index * 2 + 1
                                            val cells = result.assignments
                                                .filter { it.inputIndex == index && !it.assignedPerson.isNullOrBlank() }
                                                .map { FixedCell(colIndex = colIndex, rowIndex = it.rowIndex, personName = it.assignedPerson!!) }
                                            if (cells.isNotEmpty()) {
                                                viewModel.saveFixedCells(colIndex, cells)
                                            }
                                        }
                                    } else {
                                        // 取消固定：删除该列的 FixedCell
                                        val colIndex = index * 2 + 1
                                        viewModel.deleteFixedCellsByColumn(colIndex)
                                    }
                                }, modifier = Modifier.size(36.dp)) {
                                    Icon(
                                        if (isFixed) Icons.Default.Star else Icons.Default.StarBorder,
                                        contentDescription = if (isFixed) "取消固定" else "设为固定",
                                        tint = if (isFixed) Color(0xFFFBC02D) else Color(0xFFBDBDBD),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                            if (index < inputNames.size - 1) Divider()
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("返回") } }
    )
}

@Composable
private fun CompactHeaderButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color = LocalContentColor.current,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(28.dp)
            .height(24.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(16.dp), tint = tint)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("smart_dispatch", android.content.Context.MODE_PRIVATE)
    var selectedTab by remember { mutableIntStateOf(minOf(prefs.getInt("selectedTab", 2), 2)) }
    var showSettings by remember { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    var isTableFullscreen by remember { mutableStateOf(false) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { viewModel.importFromExcel(it) } }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri -> uri?.let { viewModel.exportToExcel(it) } }
    val focusManager = LocalFocusManager.current

    Scaffold(
        topBar = {
            if (!isTableFullscreen && selectedTab == 2) {
            // 横屏时极限压缩顶部
            if (isLandscape) {
                // 横屏极限压缩页眉：24dp，标题保留“智能排工”，字号11
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(start = 4.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("智能排工", fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 11.sp)
                    Spacer(Modifier.width(6.dp))
                    val result by viewModel.dispatchResult.collectAsState()
                    result?.let { r ->
                        Text("总${r.totalPeople}", fontSize = 10.sp, lineHeight = 10.sp, color = Color(0xFF666666))
                        Spacer(Modifier.width(3.dp))
                        Text("假${r.leaveCount}", fontSize = 10.sp, lineHeight = 10.sp, color = Color(0xFFC62828))
                        Spacer(Modifier.width(3.dp))
                        Text("分${r.assignedCount}", fontSize = 10.sp, lineHeight = 10.sp, color = Color(0xFF1976D2))
                        Spacer(Modifier.width(3.dp))
                        Text(if (r.remainingCount >= 0) "余${r.remainingCount}" else "缺${-r.remainingCount}", fontSize = 10.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold, color = if (r.remainingCount >= 0) Color(0xFF2E7D32) else Color(0xFFC62828))
                    }
                    Spacer(Modifier.weight(1f))
                    CompactHeaderButton(Icons.Default.FileUpload, "导入") { filePicker.launch(arrayOf("*/*")) }
                    CompactHeaderButton(Icons.Default.FileDownload, "导出") { exportPicker.launch("排工结果_${System.currentTimeMillis()}.xlsx") }
                    if (!BuildConfig.DEBUG) {
                        CompactHeaderButton(Icons.Default.PlayArrow, "排工", tint = Color(0xFF1976D2)) {
                            focusManager.clearFocus()
                            viewModel.autoDispatch()
                        }
                    }
                    CompactHeaderButton(Icons.Default.Settings, "设置") { showSettings = true }
                }
            } else {
                // 自定义页眉，高度40dp，无多余内边距
                Row(
                    modifier = Modifier.fillMaxWidth().height(40.dp).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("智能排工系统", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) { Icon(Icons.Default.FileUpload, "导入", modifier = Modifier.size(20.dp)) }
                    IconButton(onClick = { exportPicker.launch("排工结果_${System.currentTimeMillis()}.xlsx") }) { Icon(Icons.Default.FileDownload, "导出", modifier = Modifier.size(20.dp)) }
                    if (!BuildConfig.DEBUG) {
                        IconButton(onClick = { focusManager.clearFocus(); viewModel.autoDispatch() }) { 
                            Icon(Icons.Default.PlayArrow, "排工", modifier = Modifier.size(20.dp), tint = Color(0xFF1976D2))
                        }
                    }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "设置", modifier = Modifier.size(20.dp)) }
                }
            }
            }
        },
        bottomBar = {
            if (!isTableFullscreen) {
            val tabTitles = listOf("工序评分", "工序流程", "智能排工")
            val tabIcons = listOf(Icons.Default.Star, Icons.Default.List, Icons.Default.PlayArrow)
            val density = LocalDensity.current
            val navigationBarHeight = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
            // 底部导航栏（深灰色背景，延伸到系统导航栏）
            Column(
                modifier = Modifier.fillMaxWidth().background(Color(0xFF424242))
            ) {
                Row(modifier = Modifier.fillMaxWidth().height(if (isLandscape) 24.dp else 56.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    tabTitles.forEachIndexed { index, title ->
                        val isSelected = selectedTab == index
                        Column(
                            modifier = Modifier.weight(1f).fillMaxHeight().clickable { selectedTab = index; prefs.edit().putInt("selectedTab", index).apply() },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            if (!isLandscape) {
                                Icon(tabIcons[index], title, modifier = Modifier.size(22.dp), tint = if (isSelected) Color(0xFFFFFFFF) else Color(0xFFBDBDBD))
                                Spacer(Modifier.height(2.dp))
                            }
                            Text(title, fontSize = 11.sp, lineHeight = 11.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, color = if (isSelected) Color(0xFFFFFFFF) else Color(0xFFBDBDBD), maxLines = 1)
                        }
                    }
                }
                // 系统导航栏区域（竖屏保留，横屏极限压缩为0，给表格让空间）
                if (!isLandscape && navigationBarHeight > 0.dp) {
                    Spacer(modifier = Modifier.fillMaxWidth().height(navigationBarHeight))
                }
            }
            }
        },
        floatingActionButton = {
            if (selectedTab == 2) {
                FloatingActionButton(
                    onClick = {
                        isTableFullscreen = !isTableFullscreen
                        val activity = context as? android.app.Activity
                        activity?.let {
                            if (isTableFullscreen) {
                                @Suppress("DEPRECATION")
                                it.window.decorView.systemUiVisibility = (
                                    android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                                        or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                        or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                )
                                // 保持当前屏幕方向，只全屏
                                it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                            } else {
                                it.window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
                                it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                            }
                        }
                    },
                    modifier = Modifier.padding(start = 0.dp, top = 0.dp, end = 24.dp, bottom = 24.dp).size(36.dp)
                        .shadow(8.dp, RoundedCornerShape(18.dp), ambientColor = Color(0xFF1565C0).copy(alpha = 0.4f), spotColor = Color(0xFF1565C0).copy(alpha = 0.6f)),
                    containerColor = Color.Transparent,
                    contentColor = Color.White
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(
                            brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                colors = listOf(Color(0xFF42A5F5), Color(0xFF1976D2), Color(0xFF0D47A1))
                            ),
                            shape = RoundedCornerShape(18.dp)
                        ).border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(18.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Fullscreen, contentDescription = "全屏", modifier = Modifier.size(20.dp), tint = Color.White.copy(alpha = 0.95f))
                    }
                }
            }
        }
    ) { padding ->
        val topPadding = if (isTableFullscreen) 0.dp else padding.calculateTopPadding()
        val bottomPadding = if (isTableFullscreen) 0.dp else padding.calculateBottomPadding()
        Box(modifier = Modifier.fillMaxSize().padding(top = topPadding, bottom = bottomPadding)) {
            when (selectedTab) {
                0 -> SkillScoreTab(viewModel)
                1 -> ProcessFlowTab(viewModel)
                2 -> DispatchTab(viewModel, isLandscape)
            }
        }

        // 设置页面（全屏对话框）
        if (showSettings) {
            SettingsScreen(viewModel = viewModel, onDismiss = { showSettings = false })
        }
    }
}

// ========== Tab 2: 工序评分（固定左上角姓名单元格，行高28dp） ==========
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SkillScoreTab(viewModel: MainViewModel) {
    val persons by viewModel.allPersons.collectAsState()
    val processVer by viewModel.processVersion.collectAsState()
    // 工序列表缓存，只在 processVersion 变化时更新
    var allProcessNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var displayProcessNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoadingMore by remember { mutableStateOf(false) }
    
    LaunchedEffect(processVer) {
        val names = viewModel.allProcessNames.first()
        allProcessNames = names
        // 先显示前20列
        displayProcessNames = names.take(20)
        // 如果超过20列，延迟1秒后加载全部
        if (names.size > 20) {
            isLoadingMore = true
            delay(1000)
            displayProcessNames = names
            isLoadingMore = false
        }
    }
    val processNames = displayProcessNames
    val scoreVer by viewModel.scoreVersion.collectAsState()
    val repo = (LocalContext.current.applicationContext as DispatchApplication).repository

    if (persons.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("请先导入Excel或添加人员", color = MaterialTheme.colorScheme.outline) }
        return
    }

    // 添加工序对话框（用于空列表时）
    val showAddProcessDialog = remember { mutableStateOf(false) }

    if (processNames.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("暂无工序", color = Color(0xFF999999))
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { showAddProcessDialog.value = true }) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("添加工序")
                }
            }
        }
        // 添加工序对话框
        if (showAddProcessDialog.value) {
            var newProcessName by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showAddProcessDialog.value = false },
                title = { Text("添加工序") },
                text = {
                    OutlinedTextField(value = newProcessName, onValueChange = { newProcessName = it }, label = { Text("工序名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                },
                confirmButton = { TextButton(onClick = {
                    if (newProcessName.isNotBlank()) {
                        viewModel.addScoreProcess(newProcessName.trim())
                    }
                    showAddProcessDialog.value = false
                }) { Text("添加") } },
                dismissButton = { TextButton(onClick = { showAddProcessDialog.value = false }) { Text("取消") } }
            )
        }
        return
    }

    var scoreMap by remember { mutableStateOf<Map<Pair<Int, String>, Int>>(emptyMap()) }
    LaunchedEffect(scoreVer) {
        val allScores = repo.getAllScoresOnce()
        scoreMap = allScores.associate { (it.personId to it.processName) to it.score }
    }

    val showEditDialog = remember { mutableStateOf(false) }
    var editingPerson by remember { mutableStateOf<Person?>(null) }
    var editingProcess by remember { mutableStateOf("") }
    var currentScore by remember { mutableStateOf("0") }

    // 工序管理对话框状态
    val showProcessMenu = remember { mutableStateOf(false) }
    val showRenameDialog = remember { mutableStateOf(false) }
    val showInsertDialog = remember { mutableStateOf(false) }
    val showDeleteConfirm = remember { mutableStateOf(false) }
    var selectedProcess by remember { mutableStateOf("") }
    var renameValue by remember { mutableStateOf("") }
    var insertValue by remember { mutableStateOf("") }

    // 人员管理对话框状态
    val showPersonMenu = remember { mutableStateOf(false) }
    val showPersonEditDialog = remember { mutableStateOf(false) }
    val showPersonInsertDialog = remember { mutableStateOf(false) }
    val showPersonDeleteConfirm = remember { mutableStateOf(false) }
    var selectedPerson by remember { mutableStateOf<Person?>(null) }
    var personEditName by remember { mutableStateOf("") }
    var personEditEmployeeId by remember { mutableStateOf("") }
    var personInsertName by remember { mutableStateOf("") }
    var personInsertEmployeeId by remember { mutableStateOf("") }

    val scrollState = rememberScrollState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current

    val cellWidthPx = with(density) { 64.dp.toPx() }
    val cellHeightPx = with(density) { 24.dp.toPx() }
    val cellWidth = 64.dp
    var editingCell by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var highlightedCell by remember { mutableStateOf<Pair<Int, String>?>(null) }
    val editValues = remember { mutableStateMapOf<Pair<Int, String>, String>() }
    var showSearchDialog by remember { mutableStateOf(false) }
    var searchPersonText by remember { mutableStateOf("") }
    var searchProcessText by remember { mutableStateOf("") }
    var searchMessage by remember { mutableStateOf("") }

    fun saveScore(personId: Int, process: String, value: String) {
        val key = personId to process
        val newScore = value.toIntOrNull() ?: 0
        viewModel.setSkillScoreQuiet(personId, process, newScore)
        scoreMap = scoreMap.toMutableMap().apply { put(key, newScore) }
        editValues[key] = if (newScore > 0) newScore.toString() else ""
    }

    fun locateScoreCell() {
        val personQuery = searchPersonText.trim()
        val processQuery = searchProcessText.trim()
        val personIndex = persons.indexOfFirst { person ->
            person.name.contains(personQuery, ignoreCase = true) ||
                person.employeeId.contains(personQuery, ignoreCase = true)
        }
        val processIndex = processNames.indexOfFirst { process ->
            process.contains(processQuery, ignoreCase = true)
        }

        when {
            personQuery.isBlank() || processQuery.isBlank() -> searchMessage = "请输入员工姓名/工号和工序名称"
            personIndex < 0 -> searchMessage = "未找到员工：$personQuery"
            processIndex < 0 -> searchMessage = "未找到工序：$processQuery"
            else -> {
                val person = persons[personIndex]
                val process = processNames[processIndex]
                val key = person.id to process
                val score = scoreMap[key] ?: 0
                editValues[key] = if (score > 0) score.toString() else ""
                editingCell = key
                highlightedCell = key
                showSearchDialog = false
                searchMessage = ""
                focusManager.clearFocus(force = true)
                coroutineScope.launch {
                    listState.animateScrollToItem(personIndex)
                    delay(50) // wait for list scroll to start
                    val targetScrollX = (processIndex * cellWidthPx).toInt()
                    val maxScroll = scrollState.maxValue
                    scrollState.animateScrollTo(targetScrollX.coerceIn(0, maxScroll))
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(brush = androidx.compose.ui.graphics.Brush.verticalGradient(colors = listOf(Color(0xFFF0F4FF), Color(0xFFE8EDF5))))) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 固定左上角"工号"+"姓名"单元格 + 可滚动的工序表头
            Row(modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.width(60.dp).height(24.dp).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Text("工号", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Box(modifier = Modifier.width(60.dp).height(24.dp).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Text("姓名", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Row(modifier = Modifier.weight(1f).horizontalScroll(scrollState).background(MaterialTheme.colorScheme.primaryContainer)) {
                    processNames.forEachIndexed { index, process ->
                        Box(modifier = Modifier.width(64.dp).height(24.dp).combinedClickable(
                            onClick = {},
                            onLongClick = {
                                selectedProcess = process
                                showProcessMenu.value = true
                            }
                        ), contentAlignment = Alignment.Center) {
                            Text(process, fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Divider()
            // 数据行：工号+姓名列固定，评分列随水平滚动
            LazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
                items(persons, key = { it.id }) { person ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Box(modifier = Modifier.width(60.dp).height(24.dp).border(0.5.dp, Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) {
                            Text(person.employeeId, fontSize = 14.sp, color = Color.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Box(modifier = Modifier.width(60.dp).height(24.dp).border(0.5.dp, Color(0xFFE0E0E0)).combinedClickable(
                            onClick = {},
                            onLongClick = {
                                selectedPerson = person
                                showPersonMenu.value = true
                            }
                        ), contentAlignment = Alignment.Center) { Text(person.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        Row(modifier = Modifier.weight(1f).horizontalScroll(scrollState)) {
                            processNames.forEach { process ->
                                val score = scoreMap[Pair(person.id, process)] ?: 0
                                val cellKey = person.id to process
                                val isEditing = editingCell == cellKey
                                val isHighlighted = highlightedCell == cellKey
                                val bgColor = when {
                                    isHighlighted -> Color(0xFFFFF59D)
                                    score >= 7 -> Color(0xFFE8F5E9)
                                    score >= 4 -> Color(0xFFFFFFF3)
                                    score > 0 -> Color(0xFFFFF3E0)
                                    else -> Color(0xFFFAFAFA)
                                }
                                val borderColor = if (isEditing || isHighlighted) Color(0xFF1976D2) else Color(0xFFE0E0E0)
                                val focusRequester = remember(cellKey) { FocusRequester() }
                                
                                Box(
                                    modifier = Modifier
                                        .width(cellWidth)
                                        .height(24.dp)
                                        .background(bgColor)
                                        .border(if (isEditing || isHighlighted) 1.dp else 0.5.dp, borderColor),
                                    contentAlignment = Alignment.Center
                                ) {
                                    BasicTextField(
                                        value = if (isEditing) (editValues[cellKey] ?: "") else (if (score > 0) score.toString() else ""),
                                        onValueChange = {
                                            if (it.all { c -> c.isDigit() } && it.length <= 3) {
                                                editValues[cellKey] = it
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .focusRequester(focusRequester)
                                            .onFocusChanged { focusState ->
                                                if (focusState.isFocused) {
                                                    editValues[cellKey] = if (score > 0) score.toString() else ""
                                                    editingCell = cellKey
                                                    highlightedCell = cellKey
                                                } else if (editingCell == cellKey) {
                                                    saveScore(person.id, process, editValues[cellKey].orEmpty())
                                                    editingCell = null
                                                }
                                            },
                                        textStyle = androidx.compose.ui.text.TextStyle(
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = if (isEditing) Color(0xFF1565C0) else if (score >= 7) Color(0xFF2E7D32) else if (score > 0) Color(0xFFF57F17) else Color(0xFFBDBDBD),
                                            textAlign = TextAlign.Center
                                        ),
                                        singleLine = true,
                                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF1565C0)),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                                        keyboardActions = KeyboardActions(onDone = {
                                            saveScore(person.id, process, editValues[cellKey].orEmpty())
                                            editingCell = null
                                            focusManager.clearFocus(force = true)
                                        })
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Search button at bottom-right corner
        IconButton(
            onClick = { showSearchDialog = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 4.dp, bottom = 80.dp)
                .size(48.dp)
                .shadow(4.dp, RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
        ) {
            Icon(Icons.Default.Search, contentDescription = "搜索评分", tint = Color.White, modifier = Modifier.size(24.dp))
        }
        
        // 加载提示
        if (isLoadingMore) {
            Text(
                "加载更多工序...",
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp),
                color = Color(0xFF666666),
                fontSize = 12.sp
            )
        }
    }

    if (showSearchDialog) {
        AlertDialog(
            onDismissRequest = { showSearchDialog = false },
            title = { Text("定位评分格") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = searchPersonText,
                        onValueChange = {
                            searchPersonText = it
                            searchMessage = ""
                        },
                        label = { Text("员工姓名或工号") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = searchProcessText,
                        onValueChange = {
                            searchProcessText = it
                            searchMessage = ""
                        },
                        label = { Text("工序名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (searchMessage.isNotBlank()) {
                        Text(searchMessage, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { locateScoreCell() }) { Text("定位") } },
            dismissButton = { TextButton(onClick = { showSearchDialog = false }) { Text("取消") } }
        )
    }

    // 评分编辑对话框（已改为单元格内直接编辑，保留作为备用）
    // if (showEditDialog.value && editingPerson != null) { ... }

    // 长按工序弹出菜单
    if (showProcessMenu.value) {
        AlertDialog(
            onDismissRequest = { showProcessMenu.value = false },
            title = { Text(selectedProcess) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    TextButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        showProcessMenu.value = false
                        renameValue = selectedProcess
                        showRenameDialog.value = true
                    }) { Text("编辑工序名", modifier = Modifier.fillMaxWidth()) }
                    TextButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        showProcessMenu.value = false
                        insertValue = ""
                        showInsertDialog.value = true
                    }) { Text("在此工序前插入新工序", modifier = Modifier.fillMaxWidth()) }
                    Divider()
                    TextButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        showProcessMenu.value = false
                        showDeleteConfirm.value = true
                    }) { Text("删除工序", modifier = Modifier.fillMaxWidth(), color = Color(0xFFC62828)) }
                }
            },
            confirmButton = {}
        )
    }

    // 重命名工序对话框
    if (showRenameDialog.value) {
        AlertDialog(
            onDismissRequest = { showRenameDialog.value = false },
            title = { Text("编辑工序名") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    label = { Text("工序名") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameValue.isNotBlank() && renameValue != selectedProcess) {
                        viewModel.renameScoreProcess(selectedProcess, renameValue)
                    }
                    showRenameDialog.value = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showRenameDialog.value = false }) { Text("取消") } }
        )
    }

    // 插入工序对话框
    if (showInsertDialog.value) {
        AlertDialog(
            onDismissRequest = { showInsertDialog.value = false },
            title = { Text("在「${selectedProcess}」前插入新工序") },
            text = {
                OutlinedTextField(
                    value = insertValue,
                    onValueChange = { insertValue = it },
                    label = { Text("新工序名") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (insertValue.isNotBlank()) {
                        viewModel.addScoreProcess(insertValue, selectedProcess)
                    }
                    showInsertDialog.value = false
                }) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { showInsertDialog.value = false }) { Text("取消") } }
        )
    }

    // 删除确认对话框
    if (showDeleteConfirm.value) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm.value = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除工序「${selectedProcess}」吗？所有人员在该工序上的评分将被清除，此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteScoreProcess(selectedProcess)
                    showDeleteConfirm.value = false
                }) { Text("删除", color = Color(0xFFC62828)) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm.value = false }) { Text("取消") } }
        )
    }

    // 长按人员弹出菜单
    if (showPersonMenu.value && selectedPerson != null) {
        AlertDialog(
            onDismissRequest = { showPersonMenu.value = false },
            title = { Text(selectedPerson!!.name) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    TextButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        showPersonMenu.value = false
                        personEditName = selectedPerson!!.name
                        personEditEmployeeId = selectedPerson!!.employeeId
                        showPersonEditDialog.value = true
                    }) { Text("编辑人员信息", modifier = Modifier.fillMaxWidth()) }
                    TextButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        showPersonMenu.value = false
                        personInsertName = ""
                        personInsertEmployeeId = ""
                        showPersonInsertDialog.value = true
                    }) { Text("在此人员前插入新人员", modifier = Modifier.fillMaxWidth()) }
                    Divider()
                    TextButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        showPersonMenu.value = false
                        showPersonDeleteConfirm.value = true
                    }) { Text("删除人员", modifier = Modifier.fillMaxWidth(), color = Color(0xFFC62828)) }
                }
            },
            confirmButton = {}
        )
    }

    // 编辑人员对话框
    if (showPersonEditDialog.value && selectedPerson != null) {
        AlertDialog(
            onDismissRequest = { showPersonEditDialog.value = false },
            title = { Text("编辑人员信息") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = personEditName, onValueChange = { personEditName = it }, label = { Text("姓名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = personEditEmployeeId, onValueChange = { personEditEmployeeId = it }, label = { Text("工号") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = {
                if (personEditName.isNotBlank()) {
                    viewModel.updatePersonInfo(selectedPerson!!, personEditName.trim(), personEditEmployeeId.trim())
                }
                showPersonEditDialog.value = false
            }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { showPersonEditDialog.value = false }) { Text("取消") } }
        )
    }

    // 插入人员对话框
    if (showPersonInsertDialog.value && selectedPerson != null) {
        AlertDialog(
            onDismissRequest = { showPersonInsertDialog.value = false },
            title = { Text("在「${selectedPerson!!.name}」前插入新人员") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = personInsertName, onValueChange = { personInsertName = it }, label = { Text("姓名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = personInsertEmployeeId, onValueChange = { personInsertEmployeeId = it }, label = { Text("工号（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = {
                if (personInsertName.isNotBlank()) {
                    viewModel.insertPersonBefore(selectedPerson!!, personInsertName.trim(), personInsertEmployeeId.trim())
                }
                showPersonInsertDialog.value = false
            }) { Text("添加") } },
            dismissButton = { TextButton(onClick = { showPersonInsertDialog.value = false }) { Text("取消") } }
        )
    }

    // 删除人员确认
    if (showPersonDeleteConfirm.value && selectedPerson != null) {
        AlertDialog(
            onDismissRequest = { showPersonDeleteConfirm.value = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除「${selectedPerson!!.name}」吗？该人员的所有评分数据将被清除。") },
            confirmButton = { TextButton(onClick = { viewModel.deletePerson(selectedPerson!!); showPersonDeleteConfirm.value = false }) { Text("删除", color = Color(0xFFC62828)) } },
            dismissButton = { TextButton(onClick = { showPersonDeleteConfirm.value = false }) { Text("取消") } }
        )
    }
}

// ========== Tab 3: 工序流程（固定左上角型号名称单元格，行高28dp） ==========
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ProcessFlowTab(viewModel: MainViewModel) {
    val products by viewModel.allProducts.collectAsState()
    val repo = (LocalContext.current.applicationContext as DispatchApplication).repository
    val showInsertProductDialog = remember { mutableStateOf(false) }
    val showDeleteProductConfirm = remember { mutableStateOf(false) }
    var selectedProduct by remember { mutableStateOf<Product?>(null) }
    var insertName by remember { mutableStateOf("") }
    var insertCapacity by remember { mutableStateOf("") }
    var insertPeople by remember { mutableStateOf("") }

    var processMap by remember { mutableStateOf(emptyMap<Int, List<ProductProcess>>()) }
    val processVer by viewModel.processVersion.collectAsState()
    LaunchedEffect(products, processVer) {
        val map = mutableMapOf<Int, List<ProductProcess>>()
        for (product in products) { map[product.id] = repo.getProcessesOnce(product.id) }
        processMap = map
    }

    val maxProcesses = processMap.values.maxOfOrNull { it.size } ?: 0
    val scrollState = rememberScrollState()

    // 确保横向滚动从左侧开始
    LaunchedEffect(maxProcesses) {
        if (scrollState.value != 0) scrollState.scrollTo(0)
    }

    Box(modifier = Modifier.fillMaxSize().background(brush = androidx.compose.ui.graphics.Brush.verticalGradient(colors = listOf(Color(0xFFF0FFF4), Color(0xFFE8F5ED))))) {
        if (products.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无产品数据，请在设置中导入", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                // 固定左上角"型号名称"单元格 + 可滚动的表头
                Row(modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.width(140.dp).height(24.dp).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) { Text("型号名称", fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                    Row(modifier = Modifier.weight(1f).horizontalScroll(scrollState).background(MaterialTheme.colorScheme.primaryContainer)) {
                        Box(modifier = Modifier.width(60.dp).height(24.dp), contentAlignment = Alignment.Center) { Text("产能", fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                        Box(modifier = Modifier.width(50.dp).height(24.dp), contentAlignment = Alignment.Center) { Text("人数", fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                        repeat(maxProcesses) { i ->
                            Box(modifier = Modifier.width(72.dp).height(24.dp), contentAlignment = Alignment.Center) { Text("工序${i + 1}", fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                        }
                    }
                }
                Divider()
                // 数据行：型号名称列固定，其余列随水平滚动
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(products, key = { it.id }) { product ->
                        val processes = processMap[product.id] ?: emptyList()
                        val rowBg = if (product.isFixed) Color(0xFFFFF9C4) else Color.Transparent
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Box(modifier = Modifier.width(140.dp).height(24.dp).border(0.5.dp, Color(0xFFE0E0E0)).padding(horizontal = 4.dp).background(rowBg).combinedClickable(
                                onClick = {},
                                onLongClick = { selectedProduct = product; showInsertProductDialog.value = true }
                            ), contentAlignment = Alignment.CenterStart) { Text(product.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            Row(modifier = Modifier.weight(1f).horizontalScroll(scrollState).background(rowBg)) {
                                Box(modifier = Modifier.width(60.dp).height(24.dp).border(0.5.dp, Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) { Text(product.capacity.toString(), fontSize = 12.sp) }
                                Box(modifier = Modifier.width(50.dp).height(24.dp).border(0.5.dp, Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) { Text(product.requiredPeople.toString(), fontSize = 12.sp) }
                                // 工序列表（固定产品黄色背景）
                                for (i in 0 until maxProcesses) {
                                    val pp = processes.getOrNull(i)
                                    Box(modifier = Modifier.width(72.dp).height(24.dp).border(0.5.dp, Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) {
                                        if (pp != null) { Text(pp.processName, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
    }

    // 插入产品对话框（长按产品名称弹出）
    if (showInsertProductDialog.value && selectedProduct != null) {
        AlertDialog(
            onDismissRequest = { showInsertProductDialog.value = false },
            title = { Text("在「${selectedProduct!!.name}」前插入新产品") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = insertName, onValueChange = { insertName = it }, label = { Text("产品名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = insertCapacity, onValueChange = { if (it.all { c -> c.isDigit() }) insertCapacity = it }, label = { Text("产能") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = insertPeople, onValueChange = { if (it.all { c -> c.isDigit() }) insertPeople = it }, label = { Text("需求人数") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (insertName.isNotBlank()) {
                        viewModel.insertProductBefore(selectedProduct!!, insertName.trim(), insertCapacity.toIntOrNull() ?: 0, insertPeople.toIntOrNull() ?: 0)
                        showInsertProductDialog.value = false
                        insertName = ""
                        insertCapacity = ""
                        insertPeople = ""
                    }
                }, enabled = insertName.isNotBlank()) { Text("插入") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showInsertProductDialog.value = false
                        showDeleteProductConfirm.value = true
                    }) { Text("删除", color = Color(0xFFC62828)) }
                    TextButton(onClick = { showInsertProductDialog.value = false }) { Text("取消") }
                }
            }
        )
    }

    // 删除确认对话框
    if (showDeleteProductConfirm.value && selectedProduct != null) {
        AlertDialog(
            onDismissRequest = { showDeleteProductConfirm.value = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除「${selectedProduct!!.name}」及其所有工序吗？") },
            confirmButton = { TextButton(onClick = { viewModel.deleteProduct(selectedProduct!!); showDeleteProductConfirm.value = false }) { Text("删除", color = Color(0xFFC62828)) } },
            dismissButton = { TextButton(onClick = { showDeleteProductConfirm.value = false }) { Text("取消") } }
        )
    }
    }
}

// ========== Tab 4: 智能排工（自动排工，横屏优化，缩放功能） ==========
@Composable
fun DispatchTab(viewModel: MainViewModel, isLandscape: Boolean = false) {
    val isLoading by viewModel.isLoading.collectAsState()
    val result by viewModel.dispatchResult.collectAsState()
    val persons by viewModel.allPersons.collectAsState()
    val products by viewModel.allProducts.collectAsState()
    val inputNames by viewModel.inputNames.collectAsState()
    val focusedIndex by viewModel.focusedInputIndex.collectAsState()
    val matchedProducts by viewModel.matchedProducts.collectAsState()
    val repo = (LocalContext.current.applicationContext as DispatchApplication).repository
    var showDebugLogs by remember { mutableStateOf(false) }
    var processMap by remember { mutableStateOf(emptyMap<Int, List<ProductProcess>>()) }
    val processesByProductId = remember(processMap) { processMap.mapValues { (_, list) -> list.sortedBy { it.sortOrder } } }
    val unassignedScrollState = rememberScrollState()
    val processVer by viewModel.processVersion.collectAsState()
    LaunchedEffect(products, processVer) {
        val map = mutableMapOf<Int, List<ProductProcess>>()
        for (product in products) { map[product.id] = repo.getProcessesOnce(product.id) }
        processMap = map
    }

    // 启动时自动排工两次（仅在App启动时执行一次，切换Tab不执行）
    LaunchedEffect(Unit) {
        if (!viewModel.hasAutoDispatchedOnLaunch) {
            viewModel.hasAutoDispatchedOnLaunch = true
            kotlinx.coroutines.delay(800)
            if (products.isNotEmpty() && inputNames.any { it.isNotBlank() }) {
                viewModel.autoDispatch()
                kotlinx.coroutines.delay(500)
                viewModel.autoDispatch()
            }
        }
    }

    val productNameMap = remember(products) { products.associateBy { it.name.trim().lowercase() } }
    val selectedProducts = remember(inputNames, productNameMap) {
        inputNames.mapNotNull { name -> productNameMap[name.trim().lowercase()] }
    }

    // 按输入框索引匹配分配结果（支持相同型号多实例）
    val assignmentsByIndex = remember(result, inputNames, products) { viewModel.assignmentsByInput(result, inputNames, products) }
    val scrollState = rememberScrollState()
    val leavePeople = persons.filter { it.onLeave }

    // 调试状态下不再根据输入框变化自动排工，避免中低端手机频繁重算导致卡顿。

    // 从设置读取尺寸
    val settingsFontSize by viewModel.fontSize.collectAsState()
    val settingsRowHeight by viewModel.rowHeight.collectAsState()
    val settingsColWidth by viewModel.colWidth.collectAsState()
    val rowHeight = settingsRowHeight.dp
    val colWidth = settingsColWidth.dp
    val productWidth = colWidth * 2
    val fontSize = settingsFontSize.sp

    Column(modifier = Modifier.fillMaxSize()) {
        // 竖屏时显示统计栏（横屏时在标题栏显示）
        if (!isLandscape) {
            result?.let { r ->
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    StatItem("总人数", r.totalPeople.toString())
                    StatItem("请假", r.leaveCount.toString(), Color(0xFFC62828))
                    StatItem("已分配", r.assignedCount.toString(), Color(0xFF1976D2))
                    StatItem(if (r.remainingCount >= 0) "剩余" else "欠缺", kotlin.math.abs(r.remainingCount).toString(), if (r.remainingCount >= 0) Color(0xFF2E7D32) else Color(0xFFC62828))
                }
            }
        }
        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        // 调试日志区域（仅调试版显示）
        if (BuildConfig.DEBUG) {
            val debugLogs = result?.debugLogs ?: emptyList()
            if (debugLogs.isNotEmpty()) {
                Column(modifier = Modifier.fillMaxWidth().height(if (isLandscape) 60.dp else 80.dp).background(Color(0xFFFFF8E1)).padding(2.dp).clickable { showDebugLogs = !showDebugLogs }) {
                    Text("📋 调试日志（共${debugLogs.size}条）点击${if (showDebugLogs) "收起" else "展开"}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE65100))
                    if (showDebugLogs) {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(debugLogs.size) { index ->
                                val log = debugLogs[index]
                                val logColor = when {
                                    log.startsWith("[固定列") -> Color(0xFFE65100)
                                    log.startsWith("→") -> Color(0xFF1565C0)
                                    log.contains("缓存") || log.contains("productMap") -> Color(0xFFC62828)
                                    else -> Color(0xFF333333)
                                }
                                Text(log, fontSize = 9.sp, color = logColor, modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp))
                            }
                        }
                    } else {
                        LazyRow(modifier = Modifier.fillMaxSize()) {
                            items(debugLogs.size) { index ->
                                Text(debugLogs[index], fontSize = 8.sp, color = Color(0xFF666666), modifier = Modifier.padding(horizontal = 4.dp), maxLines = 1)
                            }
                        }
                    }
                }
            }
        }

        // 表格区域
        Box(modifier = Modifier.fillMaxSize().background(brush = androidx.compose.ui.graphics.Brush.verticalGradient(colors = listOf(Color(0xFFF5F0FF), Color(0xFFEDE8F5))))) {
            Column(modifier = Modifier.fillMaxSize()) {
                val productByInput = remember(inputNames, productNameMap) {
                    inputNames.map { name -> productNameMap[name.trim().lowercase()] }
                }
                val processNamesByInput = remember(productByInput, processesByProductId) {
                    productByInput.map { product ->
                        if (product != null) (processesByProductId[product.id] ?: emptyList()).map { it.processName } else emptyList()
                    }
                }
                val assignedPeopleByInput = remember(assignmentsByIndex, inputNames.size) {
                    inputNames.indices.map { inputIndex ->
                        assignmentsByIndex[inputIndex].orEmpty().associate { it.rowIndex to (it.assignedPerson ?: "") }
                    }
                }

                // 第一行：请假人员标题 + 输入框（两行显示）
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(scrollState).background(Color(0xFF90CAF9))) {
                    Box(modifier = Modifier.width(colWidth).height(rowHeight * 2).padding(1.dp).background(Color.White), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("请假", fontWeight = FontWeight.Bold, fontSize = fontSize, color = Color.Black)
                            Text("人员", fontWeight = FontWeight.Bold, fontSize = fontSize, color = Color.Black)
                        }
                    }
                    inputNames.forEachIndexed { index, name ->
                        val product = productByInput.getOrNull(index)
                        val headerBgColor = when {
                            viewModel.isInputSlotFixed(index) -> Color(0xFFFFD600)
                            product?.isFixed == true -> Color(0xFFFFD54F)
                            name.isNotBlank() -> Color(0xFFE3F2FD)
                            else -> Color.White
                        }
                        Box(modifier = Modifier.width(productWidth).height(rowHeight * 2).padding(1.dp).background(headerBgColor, RoundedCornerShape(2.dp))) {
                            val focusManager = LocalFocusManager.current
                            BasicTextField(
                                value = name,
                                onValueChange = { viewModel.updateInputName(index, it) },
                                modifier = Modifier.fillMaxSize().onFocusChanged { focusState ->
                                    if (focusState.isFocused) viewModel.setFocusedInput(index)
                                    else if (focusedIndex == index) viewModel.clearFocus()
                                },
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                                singleLine = false,
                                maxLines = 2,
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.Black),
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { focusManager.clearFocus() }),
                                decorationBox = { innerTextField ->
                                    Box(modifier = Modifier.fillMaxSize().background(headerBgColor, RoundedCornerShape(2.dp)).padding(horizontal = 2.dp, vertical = 4.dp), contentAlignment = Alignment.TopCenter) {
                                        if (name.isEmpty()) {
                                            Text("型号${index + 1}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFFAAAAAA), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                        }
                                        innerTextField()
                                    }
                                }
                            )
                        }
                    }
                }
                // 自动完成下拉列表（垂直展开）
                if (focusedIndex >= 0 && matchedProducts.isNotEmpty()) {
                    LazyColumn(modifier = Modifier.fillMaxWidth().height(120.dp).background(Color(0xFFF5F5F5)).padding(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(matchedProducts) { productName ->
                            Text(
                                text = productName,
                                fontSize = fontSize,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.White, RoundedCornerShape(4.dp))
                                    .clickable {
                                        viewModel.selectProduct(focusedIndex, productName)
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                color = Color(0xFF1565C0),
                                maxLines = 1
                            )
                        }
                    }
                }
                Divider()
                // 第二行：请假人名 + 产能/人数
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(scrollState)) {
                    Box(modifier = Modifier.width(colWidth).height(rowHeight).border(0.5.dp, Color(0xFFE0E0E0)).background(Color(0xFFFFCDD2)), contentAlignment = Alignment.Center) {
                        val p = leavePeople.getOrNull(0)
                        // 可编辑的请假人员单元格（有值可编辑清空，无值可输入添加）
                        var leaveInput0 by remember { mutableStateOf(p?.name ?: "") }
                        // 当外部数据变化时更新输入框
                        LaunchedEffect(p?.name) { leaveInput0 = p?.name ?: "" }
                        val focusManager = LocalFocusManager.current
                        BasicTextField(
                            value = leaveInput0,
                            onValueChange = { newValue ->
                                leaveInput0 = newValue
                                // 实时生效：清空即取消请假
                                if (newValue.isBlank() && p != null) {
                                    viewModel.toggleLeave(p)
                                }
                            },
                            modifier = Modifier.fillMaxSize().padding(2.dp).onFocusChanged { focusState ->
                                if (!focusState.isFocused) {
                                    // 失焦时自动添加请假人员
                                    if (leaveInput0.isNotBlank() && p == null) {
                                        val target = persons.find { it.name.trim() == leaveInput0.trim() }
                                        if (target != null && !target.onLeave) viewModel.toggleLeave(target)
                                    }
                                }
                            },
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = fontSize, color = Color(0xFF333333), textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                            singleLine = true,
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF1976D2)),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                                // 输入新请假人员
                                if (leaveInput0.isNotBlank() && p == null) {
                                    val target = persons.find { it.name.trim() == leaveInput0.trim() }
                                    if (target != null && !target.onLeave) viewModel.toggleLeave(target)
                                }
                                // 不调用 clearFocus()，避免触发 LaunchedEffect 重置输入值
                            }),
                            decorationBox = { innerTextField ->
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    innerTextField()
                                }
                            }
                        )
                    }
                    inputNames.forEachIndexed { index, name ->
                        val product = productByInput.getOrNull(index)
                        // 固定产品显示黄色背景
                        val cellBg = if (product?.isFixed == true) Color(0xFFFFF9C4) else Color.Transparent
                        Row(modifier = Modifier.width(productWidth).height(rowHeight).background(cellBg)) {
                            Box(modifier = Modifier.weight(1f).fillMaxHeight().border(0.5.dp, Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) {
                                Text(product?.capacity?.toString() ?: "", fontSize = fontSize, color = Color(0xFF666666))
                            }
                            Box(modifier = Modifier.weight(1f).fillMaxHeight().border(0.5.dp, Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) {
                                Text(product?.requiredPeople?.toString() ?: "", fontSize = fontSize, color = Color(0xFF666666))
                            }
                        }
                    }
                }
                Divider()
                // 数据行
                val maxProductRows = remember(processNamesByInput, assignedPeopleByInput) {
                    inputNames.indices.maxOfOrNull { index ->
                        maxOf(
                            processNamesByInput.getOrNull(index)?.size ?: 0,
                            assignedPeopleByInput.getOrNull(index)?.size ?: 0
                        )
                    } ?: 0
                }
                val maxRows = remember(maxProductRows, leavePeople.size) {
                    maxOf(maxProductRows, max(leavePeople.size - 1, 0), 1)
                }
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(maxRows, key = { rowIndex -> "schedule-row-$rowIndex" }) { rowIndex ->
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(scrollState)) {
                            Box(modifier = Modifier.width(colWidth).height(rowHeight).border(0.5.dp, Color(0xFFE0E0E0)).background(Color(0xFFFFCDD2)), contentAlignment = Alignment.Center) {
                                val person = leavePeople.getOrNull(rowIndex + 1)
                                // 可编辑的请假人员单元格（有值可编辑清空，无值可输入添加）
                                var leaveInputN by remember { mutableStateOf(person?.name ?: "") }
                                // 当外部数据变化时更新输入框
                                LaunchedEffect(person?.name) { leaveInputN = person?.name ?: "" }
                                val focusManager = LocalFocusManager.current
                                BasicTextField(
                                    value = leaveInputN,
                                    onValueChange = { newValue ->
                                        leaveInputN = newValue
                                        // 实时生效：清空即取消请假
                                        if (newValue.isBlank() && person != null) {
                                            viewModel.toggleLeave(person)
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize().padding(2.dp).onFocusChanged { focusState ->
                                        if (!focusState.isFocused) {
                                            // 失焦时自动添加请假人员
                                            if (leaveInputN.isNotBlank() && person == null) {
                                                val target = persons.find { it.name.trim() == leaveInputN.trim() }
                                                if (target != null && !target.onLeave) viewModel.toggleLeave(target)
                                            }
                                        }
                                    },
                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = fontSize, color = Color(0xFF333333), textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                                    singleLine = true,
                                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF1976D2)),
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                                        // 输入新请假人员
                                        if (leaveInputN.isNotBlank() && person == null) {
                                            val target = persons.find { it.name.trim() == leaveInputN.trim() }
                                            if (target != null && !target.onLeave) viewModel.toggleLeave(target)
                                        }
                                        // 不调用 clearFocus()，避免触发 LaunchedEffect 重置输入值
                                    }),
                                    decorationBox = { innerTextField ->
                                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            innerTextField()
                                        }
                                    }
                                )
                            }
                            inputNames.indices.forEach { index ->
                                val product = productByInput.getOrNull(index)
                                val processName = processNamesByInput.getOrNull(index)?.getOrNull(rowIndex) ?: ""
                                // 按 rowIndex 匹配分配人员（rowIndex = 3 + 工序偏移）
                                val currentRowIndex = rowIndex + 3
                                val assignedPerson = assignedPeopleByInput.getOrNull(index)?.get(currentRowIndex) ?: ""
                                // 固定产品显示深黄色背景
                                val cellBg = if (product?.isFixed == true) Color(0xFFFFD54F) else Color.Transparent

                                Row(modifier = Modifier.width(productWidth).height(rowHeight).background(cellBg)) {
                                    Box(modifier = Modifier.weight(1f).fillMaxHeight().border(0.5.dp, Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) {
                                        if (processName.isNotEmpty()) Text(processName, fontSize = fontSize, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.Black, fontWeight = FontWeight.Medium)
                                    }
                                    Box(modifier = Modifier.weight(1f).fillMaxHeight().border(0.5.dp, Color(0xFFE0E0E0)).background(Color(0xFF1976D2)), contentAlignment = Alignment.Center) {
                                        if (assignedPerson.isNotEmpty()) Text(assignedPerson, fontSize = fontSize, fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                }
                            }
                        }
                    }
                    // 未分配人员（在LazyColumn内部，可滚动看到）
                    val unassigned = result?.unassignedPeople ?: emptyList()
                    if (unassigned.isNotEmpty()) {
                        item {
                            Divider()
                            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(unassignedScrollState)) {
                                Box(modifier = Modifier.width(colWidth).height(rowHeight).border(0.5.dp, Color(0xFFE0E0E0)).background(Color(0xFFFFE0B2)), contentAlignment = Alignment.Center) {
                                    Text("未分配", fontSize = fontSize, fontWeight = FontWeight.Bold, color = Color(0xFFE65100))
                                }
                                unassigned.forEach { person ->
                                    Box(modifier = Modifier.width(colWidth).height(rowHeight).border(0.5.dp, Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) {
                                        Text(person, fontSize = fontSize, color = Color(0xFF757575), fontStyle = FontStyle.Italic)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatItem(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.primary) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = valueColor)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
