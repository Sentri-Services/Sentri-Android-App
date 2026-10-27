const fs = require('node:fs');
const path = require('node:path');
const { buildSchema, parse, print, validate, visit, visitWithTypeInfo, TypeInfo,
  getNamedType, typeFromAST, isInputObjectType, isEnumType, isScalarType, astFromValue } = require('graphql');
const root = path.resolve(__dirname, '../..');
const definitions = path.join(root, 'app/src/main/graphql');
const manifestPath = path.join(__dirname, 'contract.json');
function defaultValue(value, type) {
  if (value === undefined) return undefined;
  const ast = astFromValue(value, type);
  return ast ? print(ast) : null;
}
function sorted(value) {
  if (Array.isArray(value)) return value.map(sorted);
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map(k => [k, sorted(value[k])]));
  return value;
}
function snapshot(schemaText, operationText) {
  const schema = buildSchema(schemaText);
  const document = parse(operationText);
  const errors = validate(schema, document);
  if (errors.length) throw new Error(errors.map(e => e.message).join('\n'));
  const result = { version: 1, operations: {}, fragments: {}, fields: {}, inputs: {}, enums: {}, scalars: {} };
  const seen = new Set();
  function input(type) {
    const named = getNamedType(type);
    if (seen.has(named.name)) return;
    seen.add(named.name);
    if (isInputObjectType(named)) {
      result.inputs[named.name] = Object.fromEntries(Object.values(named.getFields()).map(f => [f.name, { type: String(f.type), default: defaultValue(f.defaultValue, f.type) }]));
      Object.values(named.getFields()).forEach(f => input(f.type));
    } else output(named);
  }
  function output(type) {
    const named = getNamedType(type);
    if (isEnumType(named)) result.enums[named.name] = named.getValues().map(v => v.name).sort();
    if (isScalarType(named)) result.scalars[named.name] = named.specifiedByURL || null;
  }
  const info = new TypeInfo(schema);
  visit(document, visitWithTypeInfo(info, {
    FragmentDefinition(node) { result.fragments[node.name.value] = print(node); },
    OperationDefinition(node) {
      if (!node.name) throw new Error('Operations must be named');
      if (result.operations[node.name.value]) throw new Error('Duplicate operation');
      result.operations[node.name.value] = print(node);
    },
    VariableDefinition(node) { input(typeFromAST(schema, node.type)); },
    Field() {
      const field = info.getFieldDef();
      if (!field) return;
      const args = Object.fromEntries(field.args.map(a => [a.name, { type: String(a.type), default: defaultValue(a.defaultValue, a.type) }]));
      result.fields[`${info.getParentType().name}.${field.name}`] = { type: String(field.type), args };
      field.args.forEach(a => input(a.type));
      output(field.type);
    },
  }));
  return sorted(result);
}
function operations() { return fs.readdirSync(definitions).filter(n => n.endsWith('.graphql')).sort().map(n => fs.readFileSync(path.join(definitions, n), 'utf8')).join('\n'); }
function check(actual, expected) {
  const differences = [];
  for (const group of ['version', 'operations', 'fragments', 'fields', 'inputs', 'enums', 'scalars']) {
    if (JSON.stringify(sorted(actual[group])) !== JSON.stringify(sorted(expected[group]))) differences.push(group);
  }
  if (differences.length) throw new Error(`Contract changed: ${differences.join(', ')}. Review the schema/operations and explicitly refresh the manifest; this is not automatically classified as a breaking change.`);
}
if (require.main === module) {
  try {
    const args = process.argv.slice(2);
    for (let i = 0; i < args.length; i++) {
      if (args[i] === '--write') continue;
      if (args[i] === '--schema' && args[i + 1] && !args[i + 1].startsWith('--')) { i++; continue; }
      throw new Error('Usage: node check.cjs [--write] [--schema path]');
    }
    const candidate = args.includes('--schema') ? args[args.indexOf('--schema') + 1] : path.join(definitions, 'schema.graphqls');
    const actual = snapshot(fs.readFileSync(candidate, 'utf8'), operations());
    if (args.includes('--write')) fs.writeFileSync(manifestPath, JSON.stringify(actual, null, 2) + '\n');
    else check(actual, JSON.parse(fs.readFileSync(manifestPath, 'utf8')));
    console.log(`GraphQL contract verified: ${Object.keys(actual.operations).length} operations, ${Object.keys(actual.fields).length} fields; candidate schema read locally.`);
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
module.exports = { snapshot, check };
