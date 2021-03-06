import org.apache.spark.ml.Pipeline
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.types.{DoubleType, StringType, StructField, StructType}
import org.apache.spark.ml.feature.{StringIndexer, VectorAssembler}
import org.apache.spark.ml.odkl.{Evaluator, RegressionEvaluator, XGBoostRegressor}
import org.apache.spark.ml.odkl.Evaluator.TrainTestEvaluator
import org.apache.spark.ml.odkl.ModelWithSummary.Block
import org.apache.spark.ml.odkl.hyperopt._

object xgboost_tutorial_with_bayes_opt {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder().master("local").getOrCreate()

    // Изначально я была не уверена в исходном коде программы,
    // поэтому решила начать с простого и делала классификацию ирисов,
    // дополнив байесовской оптимизацией.
    // Все было ок для логистической регрессии по двум классам ирисов.
    // Логистическая регрессия была взята на основании примера из презентации.
    // Я оставила этот датасет только для того,
    // чтобы проверить работоспособность программы для градиентного бустинга
    // Следующий блок  - предобработка данных:
    val schema = new StructType(Array(
      StructField("sepal length", DoubleType, true),
      StructField("sepal width", DoubleType, true),
      StructField("petal length", DoubleType, true),
      StructField("petal width", DoubleType, true),
      StructField("class", StringType, true)))
    val rawInput = spark.read.schema(schema).csv("data/iris.data")

    val stringIndexer = new StringIndexer().
      setInputCol("class").
      setOutputCol("label").
      fit(rawInput)

    val vectorAssembler = new VectorAssembler().
      setInputCols(Array("sepal length", "sepal width", "petal length", "petal width")).
      setOutputCol("features")

    val pipeline = new Pipeline().setStages(
      Array(
        stringIndexer,
        vectorAssembler
      )
    )
    val xgbInput = pipeline.fit(rawInput).transform(rawInput).select("features", "label")
    val Array(trainset, _) = xgbInput.randomSplit(Array[Double](0.7, 0.3), 18)
    //Конец обработки данных

    // Регрессия:
    val model = new XGBoostRegressor()
      .setFeatureCol("features")
      //.setNumRounds(3)
      //.setNumWorkers(1)

    /*
    // Вопрос:
    // почему Evaluator.crossValidate распознает XGBoostRegressor как SummarizableEstimator
    // а следующую модель нет?
    val model = new LinearRegression()
      .setFeaturesCol("features")
      .setLabelCol("label")
     */

    val evaluator = Evaluator.crossValidate(
      model,
      new TrainTestEvaluator(new RegressionEvaluator()),
      //numThreads = 5,
      numFolds = 5
    )

    val estimator = new StochasticHyperopt(evaluator)
      .setSearchMode(BayesianParamOptimizer.GAUSSIAN_PROCESS)
      .setParamDomains(
        ParamDomainPair(model.maxDepth, IntRangeDomain(1,10)),
        ParamDomainPair(model.lambda, DoubleRangeDomain(0.1,1.0))
      )
      .setMetricsExpression("SELECT AVG(value) FROM __THIS__ WHERE metric = 'r2' AND isTest")
      //.setNumThreads(15)
      .setMaxIter(4)
      .setNanReplacement(-999)

    val opt_result = estimator.fit(trainset)
    println(opt_result.extractParamMap())
    println(opt_result.toString())

    /*
    val row = opt_result.summary.blocks.keys
    println("tables names:")
    row.foreach(println)
    */

    println("configs")
    val configs = opt_result.summary(Block("configurations"))
    configs.show(5, true)
    /*configs.write.format("csv")
      .option("header", "true")
      .save("data/output/BO_configs_1.csv")*/


    println("metrics") // значения разных метрик по фолдам
    val metrics = opt_result.summary(Block("metrics"))
    metrics.show(5, true)
    /*metrics.write.format("csv")
      .option("header", "true")
      .save("data/output/BO_metrics_1.csv")*/


  }

}
