package com.example.smartdispatch.engine

import android.util.Log
import com.example.smartdispatch.model.*
import org.apache.poi.ss.usermodel.*
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xssf.usermodel.XSSFColor
import java.io.InputStream
import java.io.OutputStream

data class ParsedData(
    val people: List<String>,
    val peopleWithIds: List<Pair<String, String>>,  // (姓名, 工号)
    val leaveList: List<String>,
    val skillScores: Map<String, Map<String, Int>>,
    val processNames: List<String>,
    val products: Map<String, Product>
)

// 工序队列数据类
private data class ProcessQueueItem(
    val priority: Int,
    val productCol: Int,
    val rowIndex: Int,
    val processName: String,
    val productName: String,
    val inputIndex: Int
)

class DispatchEngine {

    private var allPeople: List<String> = emptyList()
    private var peopleWithIds: List<Pair<String, String>> = emptyList()  // (姓名, 工号)
    private var skillScores: Map<String, Map<String, Int>> = emptyMap()
    private var processPriority: Map<String, Int> = emptyMap()
    private var productInfo: Map<String, Product> = emptyMap()
    private var leaveList: List<String> = emptyList()
    private var fixedPositions: Map<Pair<Int, Int>, String> = emptyMap()
    private var assignedPeople: MutableSet<String> = mutableSetOf()
    private var productColumnMap: Map<String, Int> = emptyMap()
    private var parsedProcessNames: List<String> = emptyList()
    private val debugLogs = mutableListOf<String>()  // 调试日志

    fun loadFromExcel(inputStream: InputStream): Pair<Boolean, String?> {
        return try {
            val workbook = XSSFWorkbook(inputStream)

            try { loadSkillScores(workbook) } catch (e: Exception) {
                workbook.close()
                return Pair(false, "工序评分表错误: ${e.message}")
            }

            try { loadProductInfo(workbook) } catch (e: Exception) {
                workbook.close()
                return Pair(false, "工序流程表错误: ${e.message}")
            }

            try { loadMainSheet(workbook) } catch (e: Exception) {
                workbook.close()
                return Pair(false, "智能排工主表错误: ${e.message}")
            }

            workbook.close()
            Log.d("DispatchEngine", "数据加载成功: 人员${allPeople.size}人, 产品${productInfo.size}个")
            Pair(true, null)
        } catch (e: org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException) {
            Pair(false, "不是有效的.xlsx文件，请确保文件格式正确")
        } catch (e: org.apache.poi.openxml4j.exceptions.InvalidFormatException) {
            Pair(false, "文件格式无效，请使用标准的.xlsx文件")
        } catch (e: Exception) {
            Pair(false, "加载失败: ${e.message}")
        }
    }

    fun getParsedData(): ParsedData {
        return ParsedData(
            people = allPeople,
            peopleWithIds = peopleWithIds,
            leaveList = leaveList,
            skillScores = skillScores,
            processNames = parsedProcessNames,
            products = productInfo
        )
    }

    /**
     * 使用数据库传入的数据执行排工
     */
    private var fixedPeopleSet: Set<String> = emptySet()
    private val fixedAssignedPeople = mutableSetOf<String>()
    // 固定列位置→人员映射：key="槽位索引_行号", value=人员名
    private var fixedPositionMap: Map<String, String> = emptyMap()
    // 固定列输入槽位索引集合
    private var fixedSlotSet: Set<Int> = emptySet()
    // 固定单元格：(行,列) → 人员名
    private var fixedCells: Map<Pair<Int, Int>, String> = emptyMap()

    fun runWithData(
        people: List<String>,
        leaveNames: List<String>,
        products: Map<String, Product>,
        processNames: List<String>,
        fixedPeople: Set<String> = emptySet(),
        fixedPositionAssignments: Map<String, String> = emptyMap(),
        fixedSlots: Set<Int> = emptySet(),
        fixedCellMap: Map<Pair<Int, Int>, String> = emptyMap()
    ): DispatchResult {
        allPeople = people
        leaveList = leaveNames
        productInfo = products
        parsedProcessNames = processNames
        processPriority = processNames.withIndex().associate { it.value to it.index }
        fixedPeopleSet = fixedPeople
        fixedPositionMap = fixedPositionAssignments
        fixedSlotSet = fixedSlots
        fixedCells = fixedCellMap
        // 为数据库来源的数据自动构建 productColumnMap
        productColumnMap = products.keys.withIndex().associate { (index, name) -> name to (index * 2 + 1) }
        // skillScores 从数据库加载时需要通过 setSkillScores 设置
        return executeDispatch()
    }

    fun setSkillScoresData(scores: Map<String, Map<String, Int>>) {
        skillScores = scores
    }

    fun executeDispatch(): DispatchResult {
        assignedPeople.clear()
        fixedAssignedPeople.clear()
        debugLogs.clear()
        debugLogs.add("=== 排工开始 ===")
        debugLogs.add("固定列位置缓存: ${fixedPositionMap.size}条")
        debugLogs.add("固定单元格: ${fixedCells.size}条")

        val assignments = mutableListOf<ProcessAssignment>()
        val productKeys = productInfo.keys.toList()

        // ===== 第一步：处理固定单元格 =====
        val fixedCellPeople = mutableSetOf<String>()
        for ((position, personName) in fixedCells) {
            val (rowIndex, colIndex) = position
            // 检查人员是否在岗
            if (personName !in allPeople) {
                debugLogs.add("[固定单元格] $personName 不在人员名单中，跳过")
                continue
            }
            if (personName in leaveList) {
                debugLogs.add("[固定单元格] $personName 请假中，跳过")
                continue
            }
            // 找到该列对应的产品
            val productName = productKeys.find { productColumnMap[it] == colIndex }
            if (productName == null) {
                debugLogs.add("[固定单元格] 列$colIndex 无对应产品，跳过")
                continue
            }
            val product = productInfo[productName] ?: continue
            // 计算工序名称
            val processIndex = rowIndex - 3
            if (processIndex < 0 || processIndex >= product.processes.size) continue
            val processName = product.processes[processIndex]
            
            // 分配
            val slotIndex = productKeys.indexOf(productName)
            val displayName = product.name.substringAfter(":")
            assignments.add(ProcessAssignment(displayName, processName, personName, rowIndex, colIndex + 1, slotIndex))
            assignedPeople.add(personName)
            fixedCellPeople.add(personName)
            debugLogs.add("[固定单元格] $personName → 行$rowIndex 列$colIndex ($productName / $processName)")
        }

        // ===== 第二步：收集固定列在岗人员 =====
        val fixedOnDutyPeople = mutableSetOf<String>()
        for (slotIndex in fixedSlotSet) {
            if (slotIndex >= productKeys.size) continue
            val productName = productKeys[slotIndex]
            val product = productInfo[productName] ?: continue
            for ((offset, processName) in product.processes.withIndex()) {
                val rowIndex = 3 + offset
                val positionKey = "${slotIndex}_${rowIndex}"
                val person = fixedPositionMap[positionKey]
                if (person != null && person in allPeople && person !in leaveList && person !in fixedCellPeople) {
                    fixedOnDutyPeople.add(person)
                }
            }
        }
        debugLogs.add("固定列在岗人员(${fixedOnDutyPeople.size}): ${fixedOnDutyPeople.joinToString(", ")}")

        // ===== 第二步：构建排工池 =====
        val processQueue = mutableListOf<ProcessQueueItem>()
        for ((productName, product) in productInfo) {
            val productCol = productColumnMap[productName] ?: continue
            val slotIndex = productKeys.indexOf(productName)
            val displayName = product.name.substringAfter(":")
            for ((offset, processName) in product.processes.withIndex()) {
                val rowIndex = 3 + offset
                if (product.isFixed) {
                    val positionKey = "${slotIndex}_${rowIndex}"
                    val historyPerson = fixedPositionMap[positionKey]
                    if (historyPerson != null && historyPerson in allPeople && historyPerson !in leaveList) {
                        // 原地保持
                        assignments.add(ProcessAssignment(displayName, processName, historyPerson, rowIndex, productCol + 1, slotIndex))
                        assignedPeople.add(historyPerson)
                        fixedAssignedPeople.add(historyPerson)
                        debugLogs.add("[固定列留任] 槽${slotIndex} ${rowIndex}行 → $historyPerson")
                    } else {
                        if (historyPerson != null && historyPerson in leaveList) {
                            debugLogs.add("[固定列请假] 槽${slotIndex} ${rowIndex}行 → $historyPerson 请假")
                        } else {
                            debugLogs.add("[固定列新] 槽${slotIndex} ${rowIndex}行 → 无历史人员")
                        }
                        val processPriorityVal = processPriority[processName] ?: Int.MAX_VALUE
                        processQueue.add(ProcessQueueItem(processPriorityVal, productCol, rowIndex, processName, productName, slotIndex))
                    }
                } else {
                    val processPriorityVal = processPriority[processName] ?: Int.MAX_VALUE
                    processQueue.add(ProcessQueueItem(processPriorityVal, productCol, rowIndex, processName, productName, slotIndex))
                }
            }
        }
        processQueue.sortWith(compareBy({ it.priority }, { it.productCol }, { it.rowIndex }))
        debugLogs.add("排工池: ${processQueue.size}个工序待分配")

        // ===== 第三步：可分配人员池（排除请假+固定列在岗+固定单元格） =====
        val assignablePool = allPeople.filter { it !in leaveList && it !in fixedOnDutyPeople && it !in fixedCellPeople }
        debugLogs.add("可分配人员(${assignablePool.size})")

        // ===== 第四步：统一排工 =====
        for (item in processQueue) {
            val currentAvailable = assignablePool.filter { it !in assignedPeople }
            val person = findBestCandidate(item.processName, currentAvailable)
            val displayName = item.productName.substringAfter(":")
            if (person != null) {
                assignments.add(ProcessAssignment(
                    productName = displayName,
                    processName = item.processName,
                    assignedPerson = person,
                    rowIndex = item.rowIndex,
                    columnIndex = item.productCol + 1,
                    inputIndex = item.inputIndex
                ))
                assignedPeople.add(person)
                val score = skillScores[person]?.get(item.processName) ?: 0
                debugLogs.add("[排工] ${item.productCol + 1}列${item.rowIndex}行 ${item.processName} → $person (评分:$score)")
            } else {
                assignments.add(ProcessAssignment(
                    productName = displayName,
                    processName = item.processName,
                    assignedPerson = null,
                    rowIndex = item.rowIndex,
                    columnIndex = item.productCol + 1,
                    inputIndex = item.inputIndex
                ))
                debugLogs.add("[排工] ${item.productCol + 1}列${item.rowIndex}行 ${item.processName} → 留空")
            }
        }

        val assignedCount = assignedPeople.size
        val finalAvailable = allPeople.filter { it !in leaveList }
        val totalDemand = productInfo.values.sumOf { it.requiredPeople }
        val remaining = finalAvailable.size - totalDemand
        val unassigned = finalAvailable.filter { it !in assignedPeople }

        return DispatchResult(
            assignments = assignments,
            totalPeople = allPeople.size,
            leaveCount = leaveList.size,
            assignedCount = assignedCount,
            remainingCount = remaining,
            unassignedPeople = unassigned,
            statusMessage = if (remaining >= 0) "剩余${remaining}人" else "欠缺${-remaining}人",
            debugLogs = debugLogs.toList()
        )
    }

    fun exportToExcel(inputStream: InputStream, outputStream: OutputStream, result: DispatchResult): Boolean {
        return try {
            val workbook = XSSFWorkbook(inputStream)
            val mainSheet = workbook.getSheet("智能排工")
            clearOldData(mainSheet)
            for (assignment in result.assignments) {
                val row = mainSheet.getRow(assignment.rowIndex - 1) ?: mainSheet.createRow(assignment.rowIndex - 1)
                row.createCell(assignment.columnIndex - 2).setCellValue(assignment.processName)
                row.createCell(assignment.columnIndex - 1).setCellValue(assignment.assignedPerson)
            }
            writeStatusColumn(mainSheet, result)
            workbook.write(outputStream)
            workbook.close()
            true
        } catch (e: Exception) {
            Log.e("DispatchEngine", "导出失败: ${e.message}")
            false
        }
    }

    private fun loadSkillScores(workbook: Workbook) {
        val sheet = workbook.getSheet("工序评分") ?: throw Exception("缺失工序评分表")
        val headerRow = sheet.getRow(0) ?: throw Exception("工序评分表表头为空")

        val allHeaders = (0 until headerRow.lastCellNum).map {
            headerRow.getCell(it)?.stringCellValue ?: ""
        }
        Log.d("DispatchEngine", "工序评分表头: $allHeaders")

        // 工序从"姓名"列之后开始（工号=0, 姓名=1, 工序从2开始）
        val processStartCol = allHeaders.indexOfFirst { it == "姓名" }.let {
            if (it >= 0) it + 1 else 2
        }

        parsedProcessNames = (processStartCol until headerRow.lastCellNum).map {
            headerRow.getCell(it)?.stringCellValue ?: ""
        }.filter { it.isNotEmpty() }

        Log.d("DispatchEngine", "工序名列表: $parsedProcessNames, 起始列: $processStartCol")

        processPriority = parsedProcessNames.withIndex().associate { it.value to it.index }

        val people = mutableListOf<String>()
        val pWithIds = mutableListOf<Pair<String, String>>()
        val scores = mutableMapOf<String, MutableMap<String, Int>>()

        for (rowIndex in 1..sheet.lastRowNum) {
            val row = sheet.getRow(rowIndex) ?: continue
            // 读取工号（A列），兼容数字和文本格式
            val idCell = row.getCell(0)
            val employeeId = when (idCell?.cellType) {
                CellType.STRING -> idCell.stringCellValue?.trim() ?: ""
                CellType.NUMERIC -> idCell.numericCellValue.toLong().toString()
                else -> ""
            }
            // 读取姓名（B列），兼容数字和文本格式
            val nameCell = row.getCell(1)
            val name = cleanName(when (nameCell?.cellType) {
                CellType.STRING -> nameCell.stringCellValue
                CellType.NUMERIC -> nameCell.numericCellValue.toLong().toString()
                else -> null
            })
            if (name != null) {
                people.add(name)
                pWithIds.add(Pair(name, employeeId))
                scores[name] = mutableMapOf()
                for ((i, processName) in parsedProcessNames.withIndex()) {
                    val col = processStartCol + i
                    val score = getCellIntValue(row, col)
                    scores[name]!![processName] = score
                }
            }
        }

        allPeople = people
        skillScores = scores
        peopleWithIds = pWithIds
    }

    private fun loadProductInfo(workbook: Workbook) {
        val sheet = workbook.getSheet("工序流程") ?: throw Exception("缺失工序流程表")
        val headerRow = sheet.getRow(0) ?: throw Exception("工序流程表表头为空")
        val headers = (0 until headerRow.lastCellNum).map {
            headerRow.getCell(it)?.stringCellValue ?: ""
        }

        val capacityCol = headers.indexOf("产能").takeIf { it >= 0 } ?: 1
        val peopleCol = headers.indexOf("人数").takeIf { it >= 0 } ?: 2
        val processCols = headers.mapIndexed { index, s -> if (s.startsWith("工序")) index else -1 }.filter { it >= 0 }

        Log.d("DispatchEngine", "工序流程表头: $headers")

        val products = mutableMapOf<String, Product>()

        for (rowIndex in 1..sheet.lastRowNum) {
            val row = sheet.getRow(rowIndex) ?: continue
            val productName = row.getCell(0)?.stringCellValue?.trim() ?: continue
            if (productName.isEmpty()) continue

            val capacity = getCellIntValue(row, capacityCol)
            val requiredPeople = getCellIntValue(row, peopleCol)
            val processes = processCols.mapNotNull { col ->
                val cell = row.getCell(col)
                val value = when (cell?.cellType) {
                    CellType.STRING -> cell.stringCellValue?.trim()
                    CellType.NUMERIC -> cell.numericCellValue.toInt().toString()
                    else -> null
                }
                value?.takeIf { it.isNotEmpty() }
            }

            products[productName] = Product(productName, capacity, requiredPeople, processes)
        }

        productInfo = products
    }

    private fun getCellIntValue(row: Row, colIndex: Int): Int {
        val cell = row.getCell(colIndex) ?: return 0
        return try {
            cell.numericCellValue.toInt()
        } catch (e: Exception) {
            try { cell.stringCellValue?.trim()?.toIntOrNull() ?: 0 } catch (e2: Exception) { 0 }
        }
    }

    private fun loadMainSheet(workbook: Workbook) {
        val sheet = workbook.getSheet("智能排工") ?: throw Exception("缺失智能排工主表")

        val leaves = mutableListOf<String>()
        for (rowIndex in 1..DispatchConfig.MAX_PEOPLE) {
            val row = sheet.getRow(rowIndex) ?: continue
            val name = cleanName(row.getCell(0)?.stringCellValue)
            if (name != null) leaves.add(name)
        }
        leaveList = leaves

        val colMap = mutableMapOf<String, Int>()
        val fixed = mutableMapOf<Pair<Int, Int>, String>()

        val headerRow = sheet.getRow(0)
        for (col in 1 until headerRow.lastCellNum step 2) {
            val productName = headerRow.getCell(col)?.stringCellValue ?: continue
            colMap[productName] = col

            val cell = headerRow.getCell(col)
            if (isFixedColumn(cell)) {
                for (row in 2 until DispatchConfig.MAX_PEOPLE + 2) {
                    val dataRow = sheet.getRow(row) ?: continue
                    val personName = cleanName(dataRow.getCell(col + 1)?.stringCellValue)
                    if (personName != null && personName !in leaveList) {
                        fixed[Pair(col + 1, row + 1)] = personName
                        assignedPeople.add(personName)
                    }
                }
            }
        }

        productColumnMap = colMap
        fixedPositions = fixed
    }

    /**
     * 从候选池中找到某工序评分最高的人员
     */
    private fun findBestCandidate(processName: String, candidates: List<String>): String? {
        return candidates
            .mapNotNull { person ->
                val score = skillScores[person]?.get(processName) ?: 0
                if (score > 0) person to score else null
            }
            .sortedByDescending { it.second }
            .firstOrNull()?.first
    }

    private fun clearOldData(sheet: Sheet) {
        for (row in 1..23) {
            val r = sheet.getRow(row) ?: continue
            for (col in 1..19) {
                r.getCell(col)?.setCellValue(null as String?)
            }
        }
    }

    private fun writeStatusColumn(sheet: Sheet, result: DispatchResult) {
        val statusCol = productColumnMap.values.maxOrNull()?.plus(2) ?: 10
        val statusRow = sheet.getRow(1) ?: sheet.createRow(1)
        statusRow.createCell(statusCol).setCellValue(result.statusMessage)
        for ((index, person) in result.unassignedPeople.withIndex()) {
            val row = sheet.getRow(2 + index) ?: sheet.createRow(2 + index)
            row.createCell(statusCol).setCellValue(person)
        }
    }

    private fun isFixedColumn(cell: Cell?): Boolean {
        if (cell == null) return false
        val fill = cell.cellStyle.fillForegroundColorColor
        return fill is XSSFColor && fill.argbHex?.uppercase() == DispatchConfig.FIXED_COLUMN_COLOR
    }

    private fun cleanName(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val cleaned = name.trim()
        return if (cleaned.length in 2..20) cleaned else null
    }
}
