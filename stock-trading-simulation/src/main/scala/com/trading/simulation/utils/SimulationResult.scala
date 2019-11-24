package com.trading.simulation.utils

import java.time.LocalDate

import com.trading.simulation.request.StockData
import org.apache.spark.rdd.RDD

/**
  * This class represents
  * */
case class SimulationResult(financialDataRDD: RDD[(Option[LocalDate], Iterable[StockData])],
                            returns: Double,
                            totalInvestmentValue: Double,
                            sortedDates: Array[LocalDate],
                            dailyInvestmentLimit: Double,
                            tradingWindow: Int)
