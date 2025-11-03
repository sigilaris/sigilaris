package org.sigilaris.core

/** High-level assembly DSL for blockchain application construction.
  *
  * This package provides convenient tools and DSL methods for assembling blockchain applications
  * from modular blueprints, with compile-time validation and type-safe table access.
  *
  * = Core Components =
  *
  * '''[[org.sigilaris.core.assembly.BlueprintDsl]]''' - High-level mounting DSL
  *   - `mount` - Mount [[org.sigilaris.core.application.module.blueprint.ModuleBlueprint]] at single-segment path
  *   - `mountAtPath` - Mount ModuleBlueprint at multi-segment path
  *   - `mountComposed` - Mount [[org.sigilaris.core.application.module.blueprint.ComposedBlueprint]] at single-segment path
  *   - `mountComposedAtPath` - Mount ComposedBlueprint at multi-segment path
  *   - Automatic evidence requirements (PrefixFreePath, SchemaMapper, NodeStore)
  *
  * '''[[org.sigilaris.core.assembly.EntrySyntax]]''' - String interpolator for Entry creation
  *   - `entry"tableName"[K, V]` creates Entry[Name, K, V] with compile-time literal extraction
  *   - Ensures table names are string literals (not expressions)
  *   - Preserves type-level information for schema validation
  *
  * '''[[org.sigilaris.core.assembly.TablesProviderOps]]''' - Provider extraction extensions
  *   - `module.toTablesProvider` - Extract [[org.sigilaris.core.application.module.provider.TablesProvider]] from mounted module
  *   - Bridges gap between module mounting and dependency injection
  *
  * '''[[org.sigilaris.core.assembly.TablesAccessOps]]''' - Evidence derivation utilities
  *   - `deriveLookup[Schema, Name, K, V]` - Derive [[org.sigilaris.core.application.support.compiletime.Lookup]] evidence
  *   - `deriveRequires[Needs, Schema]` - Derive [[org.sigilaris.core.application.support.compiletime.Requires]] evidence
  *   - `provider.providedTable[Name, K, V]` - Access table with automatic evidence derivation
  *
  * '''[[org.sigilaris.core.assembly.PrefixFreeValidator]]''' - Runtime prefix validation
  *   - Validates that table prefixes are prefix-free at byte level
  *   - Useful for testing and debugging schema configurations
  *   - Operates on encoded prefixes (after length-prefix encoding)
  *
  * = Usage Examples =
  *
  * @example Basic module mounting:
  * ```scala
  * import org.sigilaris.core.assembly.BlueprintDsl.*
  * import org.sigilaris.core.assembly.EntrySyntax.*
  *
  * // Define schema with entry interpolator
  * val accountsEntry = entry"accounts"[String, Account]
  * val balancesEntry = entry"balances"[String, BigInt]
  *
  * // Create and mount blueprint
  * val module = mount("accounts" -> accountsBlueprint)
  *
  * // Extract provider for dependent modules
  * import org.sigilaris.core.assembly.TablesProviderOps.*
  * val provider = module.toTablesProvider
  * ```
  *
  * @example Multi-segment path mounting:
  * ```scala
  * import org.sigilaris.core.assembly.BlueprintDsl.*
  *
  * // Mount at nested path
  * val module = mountAtPath(("app", "v1", "accounts") -> blueprint)
  * ```
  *
  * @example Composed blueprint mounting:
  * ```scala
  * import org.sigilaris.core.assembly.BlueprintDsl.*
  * import org.sigilaris.core.application.module.blueprint.Blueprint
  *
  * // Compose two blueprints
  * val composed = Blueprint.composeBlueprint[IO, "app"](
  *   accountsBlueprint,
  *   balancesBlueprint
  * )
  *
  * // Mount composed blueprint (requires ModuleRoutedTx)
  * val module = mountComposed("myapp" -> composed)
  * ```
  *
  * @example Evidence derivation:
  * ```scala
  * import org.sigilaris.core.assembly.TablesAccessOps.*
  *
  * // Derive Lookup evidence without explicit summon
  * val lookup = deriveLookup[MySchema, "accounts", String, Account]
  * val table = lookup.table(tables)
  *
  * // Or use extension method for direct access
  * val accountsTable = provider.providedTable["accounts", String, Account]
  * ```
  *
  * @example Prefix validation (testing):
  * ```scala
  * import org.sigilaris.core.assembly.PrefixFreeValidator
  *
  * // Validate at compile-time
  * val result = PrefixFreeValidator.validateSchema[("app",), MySchema]
  * assert(result == PrefixFreeValidator.Valid)
  *
  * // Validate at runtime with names for debugging
  * val prefixes = List(
  *   ("accounts", tablePrefix[("app",), "accounts"]),
  *   ("balances", tablePrefix[("app",), "balances"])
  * )
  * val result2 = PrefixFreeValidator.validateWithNames(prefixes)
  * ```
  *
  * = Design Principles =
  *
  * '''Type Safety:'''
  *   - All evidence requirements checked at compile-time
  *   - String literals enforced for table names (via macro)
  *   - Automatic derivation where possible
  *
  * '''Ergonomics:'''
  *   - Concise syntax for common operations
  *   - Extension methods for natural API
  *   - Import-based feature activation
  *
  * '''Composability:'''
  *   - DSL methods compose naturally
  *   - Provider extraction enables dependency injection
  *   - Evidence derivation reduces boilerplate
  *
  * = Integration with Module System =
  *
  * The assembly package is the recommended entry point for application construction:
  *   1. Define schemas using [[org.sigilaris.core.assembly.EntrySyntax]]
  *   2. Create blueprints using [[org.sigilaris.core.application.module.blueprint]]
  *   3. Mount blueprints using [[org.sigilaris.core.assembly.BlueprintDsl]]
  *   4. Extract providers using [[org.sigilaris.core.assembly.TablesProviderOps]]
  *   5. Wire dependencies and compose modules
  *
  * @see [[org.sigilaris.core.application.module]] for underlying module system
  * @see [[org.sigilaris.core.application.state]] for table and schema types
  * @see [[org.sigilaris.core.application.support.compiletime]] for evidence types
  */
package object assembly
