package org.sigilaris.core.application

/** Backward-compatible aliases for the reorganized transactions package. */
type ModuleId[Path <: Tuple] = transactions.ModuleId[Path]
val ModuleId = transactions.ModuleId

type ModuleRoutedTx = transactions.ModuleRoutedTx

type Tx = transactions.Tx
type AccountSignature = transactions.AccountSignature
type Signed[+A <: transactions.Tx] = transactions.Signed[A]
val Signed = transactions.Signed

type TxRegistry[Txs <: Tuple] = transactions.TxRegistry[Txs]
val TxRegistry = transactions.TxRegistry
