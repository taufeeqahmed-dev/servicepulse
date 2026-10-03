package dev.taufeeqahmed.servicepulse.registration;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

final class FailureThresholdDeserializer extends ValueDeserializer<Integer> {

    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) {
        if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
            return context.reportInputMismatch(Integer.class, "failureThreshold must be an integral JSON number");
        }
        // getIntValue also rejects values outside the storage type's range. Null retains the default policy.
        return parser.getIntValue();
    }
}
