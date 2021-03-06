package com.trading.simulation.greedyInvestment

import java.time.LocalDate

import com.trading.simulation.request.StockData
import com.trading.simulation.utils.{SimulationResult, Utils}
import com.typesafe.config.Config
import org.apache.commons.lang3.StringUtils
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/**
  * This singleton class represents an investment strategy wherein the investment data on day t is made on
  * stock information available on day (t - 1). The investment amount per day is set to a constant amount which
  * is proportionately distributed across stocks depending on their closing and opening prices.
  *
  * The invested values are then computed upon based on investment data on day t and the net returns are computed within the
  * the trading window.
  **/
object GreedyInvestment {

  def run(financialDataRDD: RDD[String], sparkContext: SparkContext, simulationConfig: Config) = {

    val dateToFinancialDataRDD = getDateToFinancialData(financialDataRDD)
    val tradingWindow = simulationConfig.getInt("trading-window")
    val dailyInvestmentLimit = simulationConfig.getDouble("daily-investment-limit")

    // Get dates falling within the trading window
    val sortedDates = dateToFinancialDataRDD.map(_._1.get).sortBy(_.toEpochDay, ascending = false).take(tradingWindow)

    val dateToIdxMap = new mutable.HashMap[LocalDate, Int]()

    sortedDates.foreach {
      date =>
        dateToIdxMap.put(date, dateToIdxMap.size)
    }

    val investmentByDateRDD = reactiveInvestment(sparkContext.parallelize(dateToFinancialDataRDD.take(tradingWindow)), dateToIdxMap, sortedDates, dailyInvestmentLimit)

    val firstNRDD = sparkContext.parallelize(dateToFinancialDataRDD.take(tradingWindow))

    // Total returns
    val returns = computePnL(firstNRDD, investmentByDateRDD)

    // Total investment value depending on the trading window and daily investment limit
    val totalInvestmentValue = investmentByDateRDD.filter(_._2.toArray.length > 0).collect.length * dailyInvestmentLimit

    SimulationResult(firstNRDD, returns, totalInvestmentValue, sortedDates, dailyInvestmentLimit, tradingWindow)
  }

  /***
    * This method takes financialDataRDD which is just a string associated with a stock extracted from the .csv
    * file stored into an RDD. It groups all possible stock information for a given date.
    *
    * @param financialDataRDD Stock information as a string
    *
    * @return All stock information for a given date.
    */
  def getDateToFinancialData(financialDataRDD: RDD[String]): RDD[(Option[LocalDate], Iterable[StockData])] = {

    financialDataRDD

      .map(financialData => {

        if (!financialData.contains("timestamp")) {

          val contents = financialData.split(",")

          (Some(Utils.getLocalDateFromLocalDateStr(contents(0))), StockData(contents(9), contents(1).toDouble, contents(5).toDouble))

        } else {

          // Header line
          (None, StockData(StringUtils.EMPTY, Double.NaN, Double.NaN))

        }

      })

      // Filter out dates which are defined
      .filter(_._1.isDefined)

      // Group by dates
      .groupByKey


      // Sort in decreasing order of dates
      .sortBy(_._1.get.toEpochDay, ascending = false, 1)


  }


  /***
    * This method takes all stock information grouped for a given date and makes a decision to invest money
    * based on the proportional change in value of stock and invests a certain sum of money based on the proportion.
    *
    * @param financialDataGroupedByDate Stock information as a string
    * @param dateMap Dates mapped to their integral rank
    * @param sortedDates Dates in sorted order
    *
    * @return All investment information for a given date.
    */
  def reactiveInvestment(financialDataGroupedByDate: RDD[(Option[LocalDate], Iterable[StockData])], dateMap: mutable.HashMap[LocalDate, Int], sortedDates: Array[LocalDate], dailyInvestmentLimit: Double): RDD[(Option[LocalDate], Iterable[InvestmentData])] = {

    financialDataGroupedByDate

      .map(data => {

        val date = data._1.get

        if (!date.isEqual(sortedDates(sortedDates.length - 1))) {

          val stockData = data._2.toArray

          val investmentDataArray = new ArrayBuffer[InvestmentData]

          var sumChange = 0d

          stockData.foreach {
            stock =>
              val change = stock.getAdjustedClosingPrice - stock.getOpeningPrice

              // Invest only if change is greater than 0
              if (change > 0) {
                sumChange += change
              }
          }

          stockData.foreach {
            stock =>

              val change = stock.getAdjustedClosingPrice - stock.getOpeningPrice

              // Invest if and only if the stock gains in the day
              if (change > 0) {
                val dateIdx = dateMap.get(date)
                investmentDataArray += InvestmentData(date, sortedDates(dateIdx.get + 1), stock.getStockSymbol, change / sumChange * dailyInvestmentLimit)
              }
          }

          // (investment date -> investment array)
          (Some(sortedDates(dateMap(date) + 1)), investmentDataArray)
        } else {
          (None, None)
        }
      })

  }

  /***
    * This method joins two RDDs , @stockDataByDate and @investmentByDateRDD and computes the return for that particular date.
    * It then reduces returns across all investment dates to compute the net return.
    *
    * @param stockDataByDate Stock information as a string
    * @param investmentDataByDate Dates mapped to their integral rank
    *
    * @return Net return.
    */
  def computePnL(stockDataByDate: RDD[(Option[LocalDate], Iterable[StockData])], investmentDataByDate: RDD[(Option[LocalDate], Iterable[InvestmentData])]): Double = {

    stockDataByDate

      .join(investmentDataByDate)

      .map(stockAndInvestmentData => {

        val date = stockAndInvestmentData._1.get

        var returns = 0d

        stockAndInvestmentData._2._1.foreach {

          stockData =>

            stockAndInvestmentData._2._2.foreach {

              investmentData =>

                if (stockData.getStockSymbol == investmentData.companySymbol) {

                  returns += (1 + (stockData.getAdjustedClosingPrice - stockData.getOpeningPrice) / stockData.getOpeningPrice) * investmentData.investedAmount

                }
            }
        }
        (Some(date), returns)
      })

      .map(_._2)

      .reduce(_ + _)
  }


}
