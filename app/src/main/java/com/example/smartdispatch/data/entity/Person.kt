package com.example.smartdispatch.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "persons")
data class Person(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val employeeId: String = "",  // 工号
    val onLeave: Boolean = false,
    val insertOrder: Int = 0
)
