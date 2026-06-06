package com.ssuai.domain.academic.connector;

import com.ssuai.domain.academic.dto.AcademicPolicyCorpusSnapshot;

public interface AcademicPolicyConnector {

    AcademicPolicyCorpusSnapshot loadCorpus(boolean live);
}
