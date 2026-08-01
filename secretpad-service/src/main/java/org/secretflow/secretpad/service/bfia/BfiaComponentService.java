package org.secretflow.secretpad.service.bfia;

import org.secretflow.secretpad.service.model.graph.CompListVO;

import java.util.List;
import java.util.Map;

public interface BfiaComponentService {
    List<Map<String,Object>> listComponents();
    Map<String, CompListVO> listCompListVO();
}


