package com.amazonaws.emr.titanic
import org.apache.spark.sql.SparkSession
import org.apache.spark.ml._
import org.apache.spark.ml.feature._
import org.apache.spark.ml.classification.RandomForestClassifier
import org.apache.spark.sql.functions._
import org.apache.spark.sql.SaveMode
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.types.IntegerType
import org.apache.spark.ml.tuning.ParamGridBuilder
import org.apache.spark.ml.param.ParamMap
import org.apache.spark.ml.tuning.CrossValidator
import org.apache.spark.ml.evaluation.BinaryClassificationEvaluator
import org.apache.spark.mllib.evaluation.MulticlassMetrics
import org.apache.spark._

/**
 * Random Forest for predicting survival in the titanic ship
 *
 */

object Titanic {
  def main(args: Array[String]) {
    if (args.length < 2) {
      println("File path must be passed. " + args.length)
      System.exit(-1)
    }

    val sparkSession = SparkSession.builder
      .appName("example")
      .getOrCreate()
    import sparkSession.implicits._

    sparkSession.sparkContext.setLogLevel("ERROR")

    val trainDataset = args(0)
    val outputPath = args(1)

    //load train df
    val df = sparkSession.read.option("header", "true").option("inferSchema", "true").csv(trainDataset)
    df.printSchema()

    //handle missing values
    val meanValue = df.agg(mean(df("Age"))).first.getDouble(0)
    val fixedDf = df.na.fill(meanValue, Array("Age"))
    //test and train split
    val dfs = fixedDf.randomSplit(Array(0.7, 0.3))
    val trainDf = dfs(0).withColumnRenamed("Survived", "label")
    val crossDf = dfs(1)

    // create pipeline stages for handling categorical
    val genderStages = handleCategorical("Sex")
    val embarkedStages = handleCategorical("Embarked")
    val pClassStages = handleCategorical("Pclass")

    //columns for training
    val cols = Array("Sex_onehot", "Embarked_onehot", "Pclass_onehot", "SibSp", "Parch", "Age", "Fare")
    val vectorAssembler = new VectorAssembler().setInputCols(cols).setOutputCol("features")

    //algorithm stage
    val randomForestClassifier = new RandomForestClassifier()
    //pipeline
    val preProcessStages = genderStages ++ embarkedStages ++ pClassStages ++ Array(vectorAssembler)
    val pipeline = new Pipeline().setStages(preProcessStages ++ Array(randomForestClassifier))

    val model = pipeline.fit(trainDf)
    println("train accuracy with pipeline: " + accuracyScore(model.transform(trainDf), "label", "prediction"))
    println("test accuracy with pipeline: " + accuracyScore(model.transform(crossDf), "Survived", "prediction"))

    //cross validation
    val paramMap = new ParamGridBuilder()
      .addGrid(randomForestClassifier.impurity, Array("gini", "entropy"))
      .addGrid(randomForestClassifier.maxDepth, Array(1, 2))
      .addGrid(randomForestClassifier.minInstancesPerNode, Array(1, 2))
      .build()

    val cvModel = crossValidation(pipeline, paramMap, trainDf)
    println("train accuracy with cross validation: " + accuracyScore(cvModel.transform(trainDf), "label", "prediction"))
    println("test accuracy with cross validation: " + accuracyScore(cvModel.transform(crossDf), "Survived", "prediction"))

    // val testDf = sparkSession.read.option("header", "true").option("inferSchema", "true").csv("src/main/resources/titanic/test.csv")
    val testDf = crossDf
    val fareMeanValue = df.agg(mean(df("Fare"))).first.getDouble(0)
    val fixedOutputDf = crossDf.na.fill(meanValue, Array("age")).na.fill(fareMeanValue, Array("Fare"))

    generateOutputFile(fixedOutputDf, outputPath, cvModel)
  }

  def generateOutputFile(testDF: DataFrame, outputPath: String, model: Model[_]) = {
    val scoredDf = model.transform(testDF)
    scoredDf.printSchema()
    val outputDf = scoredDf.select("PassengerId", "prediction", "Survived")
    val castedDf = outputDf.select(outputDf("PassengerId"), outputDf("prediction").cast(IntegerType).as("predicted"), outputDf("Survived").as("target"))
    castedDf.write.format("csv").option("header", "false").mode(SaveMode.Overwrite).save(outputPath)
  }

  def crossValidation(pipeline: Pipeline, paramMap: Array[ParamMap], df: DataFrame): Model[_] = {
    val cv = new CrossValidator()
      .setEstimator(pipeline)
      .setEvaluator(new BinaryClassificationEvaluator)
      .setEstimatorParamMaps(paramMap)
      .setNumFolds(5)
    cv.fit(df)
  }

  def handleCategorical(column: String): Array[PipelineStage] = {
    val stringIndexer = new StringIndexer().setInputCol(column)
      .setOutputCol(s"${column}_index")
      .setHandleInvalid("skip")
    val oneHot = new OneHotEncoder().setInputCol(s"${column}_index").setOutputCol(s"${column}_onehot")
    Array(stringIndexer, oneHot)
  }

  def accuracyScore(df: DataFrame, label: String, predictCol: String) = {
    val rdd = df.select(predictCol,label).rdd.map(row ⇒ (row.getDouble(0), row.getInt(1).toDouble))
    new MulticlassMetrics(rdd).accuracy
  }
}
