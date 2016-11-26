package jupyter

package object spark {

  lazy val sparkConf = Spark.inst.sparkConf
  lazy val sc = Spark.inst.sc
  lazy val sqlContext = Spark.inst.sqlContext

}
