package mill.internal

import mill.api.Strict.Agg

import scala.collection.mutable

/**
 * A map from keys to collections of values: you can assign multiple values
 * to any particular key. Also allows lookups in both directions: what values
 * are assigned to a key or what key a value is assigned to.
 */
trait MultiBiMap[K, V] {
  def containsValue(v: V): Boolean
  def lookupKey(k: K): Agg[V]
  def lookupValue(v: V): K
  def lookupValueOpt(v: V): Option[K]
  def add(k: K, v: V): Unit
  def removeAll(k: K): Agg[V]
  def addAll(k: K, vs: IterableOnce[V]): Unit
  def keys(): Iterator[K]
  def items(): Iterator[(K, Agg[V])]
  def values(): Iterator[Agg[V]]
  def keyCount: Int
}

object MultiBiMap {

  class Mutable[K, V]() extends MultiBiMap[K, V] {
    private[this] val valueToKey = mutable.LinkedHashMap.empty[V, K]
    private[this] val keyToValues = mutable.LinkedHashMap.empty[K, Agg.Mutable[V]]
    def containsValue(v: V): Boolean = valueToKey.contains(v)
    def lookupKey(k: K): Agg.Mutable[V] = keyToValues(k)
    def lookupKeyOpt(k: K): Option[Agg.Mutable[V]] = keyToValues.get(k)
    def lookupValue(v: V): K = valueToKey(v)
    def lookupValueOpt(v: V): Option[K] = valueToKey.get(v)
    def add(k: K, v: V): Unit = {
      valueToKey(v) = k
      keyToValues.getOrElseUpdate(k, new Agg.Mutable[V]()).append(v)
    }
    def removeAll(k: K): Agg[V] = keyToValues.get(k) match {
      case None => Agg()
      case Some(vs) =>
        vs.iterator.foreach(valueToKey.remove)
        keyToValues.remove(k)
        vs
    }
    def addAll(k: K, vs: IterableOnce[V]): Unit = vs.iterator.foreach(this.add(k, _))

    def keys(): Iterator[K] = keyToValues.keysIterator

    def values(): Iterator[Agg.Mutable[V]] = keyToValues.valuesIterator

    def items(): Iterator[(K, Agg.Mutable[V])] = keyToValues.iterator

    def keyCount: Int = keyToValues.size
  }
}
