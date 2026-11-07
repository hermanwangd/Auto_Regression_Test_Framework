package com.specdriven.regression.runner;
import java.io.IOException;
import java.util.Map;
/** Finite public codes. Exception messages, paths and parser snippets are never exported. */
final class RunnerDiagnostic {
 private static final Map<String,String> KNOWN=Map.ofEntries(
  Map.entry("unknown_or_missing_fields","SCHEMA_FIELDS_INVALID"),
  Map.entry("object_required","SCHEMA_FIELDS_INVALID"),
  Map.entry("independent_lock_roots_options_required","LOCK_AND_TRUSTED_ROOT_BINDINGS_REQUIRED"),
  Map.entry("independent_collection_spec_required","COLLECTION_BINDING_REQUIRED"),
  Map.entry("independent_pin_required","INDEPENDENT_PIN_REQUIRED"),
  Map.entry("independent_pin_mismatch","INDEPENDENT_PIN_MISMATCH"),
  Map.entry("independent_lock_pin_mismatch","INDEPENDENT_LOCK_PIN_MISMATCH"),
  Map.entry("protected_lock_binding_mismatch","PROTECTED_LOCK_BINDING_MISMATCH"),
  Map.entry("private_key_log_excluded","PRIVATE_KEY_LOG_EXCLUDED"),
  Map.entry("secret_or_private_key_excluded","SECRET_MATERIAL_EXCLUDED"),
  Map.entry("committed_source_mode_not_regular","COMMITTED_SOURCE_MODE_NOT_REGULAR"),
  Map.entry("saved_reread_mismatch","SAVED_BYTES_CHANGED"),
  Map.entry("SOURCE_OWNER_UNBOUND","SOURCE_OWNER_UNBOUND"),
  Map.entry("SUT_DEPLOYED_REVISION_MISMATCH","SUT_DEPLOYED_REVISION_MISMATCH"),
  Map.entry("NATIVE_RUNTIME_BINDING_UNBOUND_NO_RESOURCE_AUTHORITY","NATIVE_RUNTIME_BINDING_UNBOUND"),
  Map.entry("authorized_runtime_image_sut_binding_missing_NO_NATIVE_EXECUTION","NATIVE_RUNTIME_BINDING_UNBOUND"),
  Map.entry("collection_deadline_exceeded","COLLECTION_DEADLINE_EXCEEDED"),
  Map.entry("collection_deadline_exceeded_before_SAVE","COLLECTION_DEADLINE_EXCEEDED"));
 static String code(Throwable error){
  String message=error.getMessage();String known=message==null?null:KNOWN.get(message);if(known!=null)return known;
  if(error instanceof IOException)return "UNSAFE_OR_UNREADABLE_ARTIFACT";
  if(error instanceof SecurityException)return "OPERATION_DENIED";
  return "INVALID_RUNNER_BINDING_OR_ARTIFACT";
 }
 private RunnerDiagnostic(){}
}
